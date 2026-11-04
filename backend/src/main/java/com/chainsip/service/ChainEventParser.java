package com.chainsip.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fisco.bcos.sdk.crypto.hash.Keccak256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链上事件解析。
 *
 * <p>SDK 2.x 的 {@code response.getEvents()} 返回的是「事件名 → 二维数组」的原始 JSON，
 * 例如 {@code {"PaymentMade":[[...],[...]]}}：外层数组是"该事件的多次发射"，内层数组是
 * "这一次发射的非索引参数"（按 ABI inputs 顺序）。<b>索引参数（如 employee / sponsor 地址）
 * 不在这串里</b>，它们在回执日志的 {@code topics} 里（topics[0] 是事件签名，topics[1..] 是索引参数）。
 *
 * <p>因此这里结合两路数据：
 * <ol>
 *   <li>从 ABI 建立「事件名 → 输入参数（名称 / 类型 / 是否 indexed）」；</li>
 *   <li>非索引参数取 {@code eventsJson} 的位置数组，索引参数取 {@code logsJson} 的 topics 解码；</li>
 *   <li>两者拼回带字段名的对象，再抽取常用列（职工 / 单位 / 金额）。</li>
 * </ol>
 *
 * <p>抽不到的字段留空，不影响审计——原始 JSON 始终完整保留。logs 缺失时退化为"仅事件名 + 非索引参数"，
 * 仍能保证事件被索引（不再出现空表）。
 */
@Component
public class ChainEventParser {

    private static final Logger log = LoggerFactory.getLogger(ChainEventParser.class);

    private final ObjectMapper objectMapper;

    /** 事件名 → 输入参数定义（按 ABI inputs 顺序）。 */
    private final Map<String, EventDef> eventDefs = new HashMap<>();

    /** 事件签名 topic（64 位小写 hex，无 0x）→ 事件名。首次解析时惰性构建（需原生 keccak）。 */
    private volatile Map<String, String> topicToEvent;

    public ChainEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 启动时加载两份合约 ABI，建立事件参数映射（纯 JSON 解析，不依赖链/原生库）。 */
    @PostConstruct
    void loadAbi() {
        loadAbi("abi/ChainSIP.abi");
        loadAbi("abi/ChainSIPTransfer.abi");
    }

    private void loadAbi(String classpath) {
        try {
            JsonNode root = objectMapper.readTree(new ClassPathResource(classpath).getInputStream());
            if (root == null || !root.isArray()) {
                return;
            }
            for (JsonNode entry : root) {
                if (!"event".equals(entry.path("type").asText(null))) {
                    continue;
                }
                String name = entry.path("name").asText(null);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                List<ParamDef> inputs = new ArrayList<>();
                for (JsonNode in : entry.path("inputs")) {
                    inputs.add(new ParamDef(
                            in.path("name").asText(""),
                            in.path("type").asText(""),
                            in.path("indexed").asBoolean(false)));
                }
                eventDefs.putIfAbsent(name, new EventDef(name, inputs));
            }
            log.info("已加载事件定义 {} 个（{}）", eventDefs.size(), classpath);
        } catch (Exception e) {
            log.warn("加载 ABI 事件定义失败（事件将退化为仅保留原文）：{}", e.getMessage());
        }
    }

    public List<ParsedEvent> parse(String eventsJson) {
        return parse(eventsJson, null);
    }

    public List<ParsedEvent> parse(String eventsJson, String logsJson) {
        List<ParsedEvent> events = new ArrayList<>();
        if (eventsJson == null || eventsJson.trim().isEmpty() || "[]".equals(eventsJson.trim())) {
            return events;
        }
        try {
            JsonNode eventsRoot = objectMapper.readTree(eventsJson);
            JsonNode logsRoot = (logsJson == null || logsJson.trim().isEmpty())
                    ? null : objectMapper.readTree(logsJson);

            // 事件名 → 该事件的非索引参数发射列表
            Map<String, List<JsonNode>> emissions = new LinkedHashMap<>();
            collectEmissions(eventsRoot, emissions);

            // 日志 → 已解码索引参数（事件名 + topics[1..]）
            List<LogEvent> logs = decodeLogs(logsRoot);

            Map<String, Integer> cursor = new HashMap<>();
            for (LogEvent logEvent : logs) {
                EventDef def = eventDefs.get(logEvent.eventName);
                int idx = cursor.getOrDefault(logEvent.eventName, 0);
                cursor.put(logEvent.eventName, idx + 1);
                JsonNode nonIndexed = null;
                List<JsonNode> list = emissions.get(logEvent.eventName);
                if (list != null && idx < list.size()) {
                    nonIndexed = list.get(idx);
                }
                events.add(build(def, logEvent.indexedValues, nonIndexed, logEvent.eventName));
            }

            // 兜底：没有日志（回执无 logs）时，退化为"仅事件名 + 非索引参数"，仍保证事件被索引
            if (logs.isEmpty()) {
                for (Map.Entry<String, List<JsonNode>> entry : emissions.entrySet()) {
                    EventDef def = eventDefs.get(entry.getKey());
                    for (JsonNode emission : entry.getValue()) {
                        events.add(build(def, java.util.Collections.emptyList(), emission, entry.getKey()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("事件 JSON 解析失败，将仅保留原文：{}", e.getMessage());
            ParsedEvent fallback = new ParsedEvent();
            fallback.eventName = "UNKNOWN";
            fallback.payload = eventsJson;
            events.add(fallback);
        }
        return events;
    }

    /** 收集"事件名 → 非索引参数发射列表"。兼容三种形态（见类注释与历史形态）。 */
    private void collectEmissions(JsonNode node, Map<String, List<JsonNode>> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectEmissions(child, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        // 历史形态一：{"eventName":"PaymentMade","eventData":{...}}
        if (node.hasNonNull("eventName")) {
            String name = node.get("eventName").asText();
            JsonNode data = node.hasNonNull("eventData") ? node.get("eventData") : node;
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(data);
            return;
        }
        // 形态二/三：{"PaymentMade":{...}} 或 {"PaymentMade":[[...],[...]]}
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray()) {
                for (JsonNode emission : value) {
                    out.computeIfAbsent(field, k -> new ArrayList<>()).add(emission);
                }
            } else {
                out.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
            }
        }
    }

    /** 从回执日志解码出「事件名 + 索引参数（topics[1..]）」。 */
    private List<LogEvent> decodeLogs(JsonNode logsRoot) {
        List<LogEvent> out = new ArrayList<>();
        if (logsRoot == null || !logsRoot.isArray()) {
            return out;
        }
        Map<String, String> topicIndex = topicIndex();
        for (JsonNode logNode : logsRoot) {
            JsonNode topics = logNode.get("topics");
            if (topics == null || !topics.isArray() || topics.size() == 0) {
                continue;
            }
            String signature = normalizeTopic(topics.get(0).asText(null));
            String eventName = topicIndex.get(signature);
            if (eventName == null) {
                continue;
            }
            LogEvent logEvent = new LogEvent();
            logEvent.eventName = eventName;
            logEvent.indexedValues = new ArrayList<>();
            for (int i = 1; i < topics.size(); i++) {
                logEvent.indexedValues.add(topics.get(i).asText(null));
            }
            out.add(logEvent);
        }
        return out;
    }

    /** 惰性构建「签名 topic → 事件名」（首次解析时才调用原生 keccak，链/原生库此时已就绪）。 */
    private Map<String, String> topicIndex() {
        if (topicToEvent == null) {
            synchronized (this) {
                if (topicToEvent == null) {
                    Map<String, String> map = new HashMap<>();
                    for (EventDef def : eventDefs.values()) {
                        String topic = def.signatureTopic();
                        if (topic != null) {
                            map.put(topic, def.name);
                        }
                    }
                    topicToEvent = map;
                }
            }
        }
        return topicToEvent;
    }

    /**
     * 把索引参数（来自 topics）+ 非索引参数（来自 events 数组 / 对象）拼成带字段名的对象，
     * 再抽常用列。
     */
    private ParsedEvent build(EventDef def, List<String> indexedValues, JsonNode nonIndexed, String eventName) {
        ObjectNode named = objectMapper.createObjectNode();
        int indexedIdx = 0;
        int nonIndexedIdx = 0;
        if (def != null) {
            for (ParamDef p : def.inputs) {
                if (p.indexed) {
                    String raw = indexedIdx < indexedValues.size() ? indexedValues.get(indexedIdx) : null;
                    indexedIdx++;
                    String decoded = decodeTopicValue(p.type, raw);
                    if (decoded != null) {
                        named.put(p.name, decoded);
                    }
                } else if (nonIndexed != null && nonIndexed.isObject()) {
                    JsonNode v = nonIndexed.get(p.name);
                    if (v != null && !v.isNull()) {
                        named.set(p.name, v);
                    }
                } else if (nonIndexed != null && nonIndexed.isArray()) {
                    JsonNode v = nonIndexedIdx < nonIndexed.size() ? nonIndexed.get(nonIndexedIdx) : null;
                    nonIndexedIdx++;
                    if (v != null && !v.isNull()) {
                        named.set(p.name, v);
                    }
                }
            }
        } else if (nonIndexed != null && nonIndexed.isObject()) {
            Iterator<String> names = nonIndexed.fieldNames();
            while (names.hasNext()) {
                String n = names.next();
                named.set(n, nonIndexed.get(n));
            }
        }

        ParsedEvent event = new ParsedEvent();
        event.eventName = eventName;
        event.payload = named.size() > 0 ? named.toString()
                : (nonIndexed == null ? "[]" : nonIndexed.toString());
        fillFields(event, named);
        return event;
    }

    /** 从带字段名的对象里抽取常用列。 */
    private void fillFields(ParsedEvent event, JsonNode data) {
        event.employeeAddress = findText(data, "employee", "applicant");
        event.sponsorAddress = findText(data, "sponsor");
        // 金额列语义：优先取显式 amount；养老金事件没有 amount，但有 totalAmount（本次发放合计），
        // 因此把它排在 personalAmount 之前，避免列表里只显示"个人账户部分"而漏掉统筹部分。
        event.amount = findText(data, "amount", "totalAmount", "personalAmount");
        event.personalAmount = findText(data, "personalAmount", "personalFund");
        event.overallAmount = findText(data, "overallAmount", "overallFund");
    }

    /** 解码一个 32 字节 topic：address 取末 40 位 hex，uint 转十进制，其余原样返回。 */
    private String decodeTopicValue(String type, String topic) {
        if (topic == null || topic.isEmpty()) {
            return null;
        }
        String hex = normalizeTopic(topic);
        if (hex == null) {
            return null;
        }
        if ("address".equals(type)) {
            if (hex.length() >= 40) {
                return "0x" + hex.substring(hex.length() - 40);
            }
            return null;
        }
        if (type.startsWith("uint") || type.startsWith("int")) {
            try {
                return new BigInteger(hex, 16).toString();
            } catch (NumberFormatException e) {
                return topic;
            }
        }
        if ("bool".equals(type)) {
            try {
                return new BigInteger(hex, 16).signum() != 0 ? "true" : "false";
            } catch (NumberFormatException e) {
                return topic;
            }
        }
        return topic;
    }

    /** 深度优先查找第一个匹配字段名的值（只要有一处命中就返回）。 */
    private String findText(JsonNode node, String... keys) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && (value.isTextual() || value.isNumber() || value.isBoolean())) {
                    return value.asText();
                }
            }
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                String found = findText(children.next(), keys);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findText(child, keys);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 归一化 topic：去 0x、转小写、补足 64 位。 */
    private static String normalizeTopic(String topic) {
        if (topic == null) {
            return null;
        }
        String hex = topic.startsWith("0x") || topic.startsWith("0X") ? topic.substring(2) : topic;
        hex = hex.toLowerCase();
        if (hex.length() < 64) {
            hex = String.format("%64s", hex).replace(' ', '0');
        }
        return hex;
    }

    /** 事件定义：名称 + 输入参数。 */
    private static final class EventDef {
        final String name;
        final List<ParamDef> inputs;
        volatile String signatureTopic;

        EventDef(String name, List<ParamDef> inputs) {
            this.name = name;
            this.inputs = inputs;
        }

        /** 计算事件签名 topic（keccak256("Name(type1,type2,...)")），失败返回 null。 */
        String signatureTopic() {
            if (signatureTopic == null) {
                StringBuilder sb = new StringBuilder(name).append('(');
                for (int i = 0; i < inputs.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(inputs.get(i).type);
                }
                sb.append(')');
                try {
                    String hash = Keccak256.calculateHash(sb.toString().getBytes(StandardCharsets.UTF_8));
                    signatureTopic = ChainEventParser.normalizeTopic(hash);
                } catch (Throwable e) {
                    log.warn("计算事件签名失败（{}）：{}", name, e.getMessage());
                    return null;
                }
            }
            return signatureTopic;
        }
    }

    /** 单个输入参数定义。 */
    private static final class ParamDef {
        final String name;
        final String type;
        final boolean indexed;

        ParamDef(String name, String type, boolean indexed) {
            this.name = name;
            this.type = type;
            this.indexed = indexed;
        }
    }

    /** 单条回执日志解码结果。 */
    private static final class LogEvent {
        String eventName;
        List<String> indexedValues;
    }

    /** 解析后的事件。 */
    public static class ParsedEvent {
        public String eventName;
        public String payload;
        public String employeeAddress;
        public String sponsorAddress;
        public String amount;
        public String personalAmount;
        public String overallAmount;
    }
}
