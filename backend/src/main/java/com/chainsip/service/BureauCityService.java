package com.chainsip.service;

import com.chainsip.chain.dto.CallResult;
import com.chainsip.config.ChainProperties;
import com.chainsip.entity.InstitutionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 社保局「服务城市」的只读查询与默认值来源。
 *
 * <p><b>为什么单拉一个服务</b>：一个社保局在链上绑定哪些服务城市，是"缴费单位所在城市"这件事
 * 唯一权威的来源（转移合约 {@code getBureau(address)} 返回的最后一个字段 {@code string[] cities}）。
 * 以前这个信息只在"开号时校验一下"的地方顺带读一次、读不到就当空；现在它同时被
 * {@code POST /api/bureau/accounts}（自动带出城市）和两个只读接口
 * （{@code GET /api/bureau/cities} / {@code GET /api/admin/cities}）使用，
 * 因此把"怎么读、读不到算什么"集中在一处，避免三份实现各自漂移。
 *
 * <p><b>读不到 ≠ 没有城市</b>：这里的查询结果刻意带一个 {@code readable} 标记，把
 * "链上确实没有服务城市"（可读但列表为空）与"根本读不出来"（链未就绪 / 合约未配置 /
 * 该地址在转移合约里 {@code UnknownBureau}）区分开——上层据此决定是"降级放行"还是"明确报错"。
 *
 * <p>所有方法都不向上抛链异常：只读接口契约是"链未就绪返回空数组 + 200，不要 500"。
 */
@Service
public class BureauCityService {

    private static final Logger log = LoggerFactory.getLogger(BureauCityService.class);

    private static final String TRANSFER = ChainProperties.TRANSFER;

    /** 汇总全部城市时每次向 {@code getBureauList} 要多少个社保局（合约的 limit 参数）。 */
    private static final int BUREAU_PAGE_SIZE = 200;

    /** 最多翻多少页：`getBureauList` 的 nextOffset 异常时不至于死循环。 */
    private static final int MAX_PAGES = 50;

    private final ChainTxService chainTxService;
    private final InstitutionKeyService institutionKeyService;
    private final RedisCacheService cache;

    public BureauCityService(ChainTxService chainTxService, InstitutionKeyService institutionKeyService,
                             RedisCacheService cache) {
        this.chainTxService = chainTxService;
        this.institutionKeyService = institutionKeyService;
        this.cache = cache;
    }

    // ------------------------------------------------------------------
    // 查询结果
    // ------------------------------------------------------------------

    /** 一次服务城市查询的结果：城市列表 + 这次读链到底成不成功。 */
    public static final class CityLookup {

        private final List<String> cities;
        private final boolean readable;
        private final String failureReason;

        private CityLookup(List<String> cities, boolean readable, String failureReason) {
            this.cities = cities;
            this.readable = readable;
            this.failureReason = failureReason;
        }

        static CityLookup readable(List<String> cities) {
            return new CityLookup(cities, true, "");
        }

        static CityLookup unreadable(String reason) {
            return new CityLookup(new ArrayList<String>(), false, reason == null ? "" : reason);
        }

        /** 链上读到（可能为空）的服务城市；读链失败时为空列表。 */
        public List<String> getCities() {
            return cities;
        }

        /** true 表示这次确实读到了链上回答（哪怕城市数组是空的）。 */
        public boolean isReadable() {
            return readable;
        }

        /** 读链失败的原因（readable=true 时为空串）。 */
        public String getFailureReason() {
            return failureReason;
        }
    }

    // ------------------------------------------------------------------
    // 单个社保局
    // ------------------------------------------------------------------

    /**
     * 按机构编码读该社保局在链上的服务城市。
     *
     * <p>机构编码来自登录账号的 {@code institutionCode}（即"这次用谁的私钥"的答案）；
     * 该编码对应的链上地址就是 {@code getBureau(address)} 的参数。
     */
    public CityLookup lookupByInstitutionCode(String institutionCode) {
        String code = institutionCode == null ? null : institutionCode.trim();
        if (code == null || code.isEmpty()) {
            return CityLookup.unreadable("当前账号未关联机构链账户（institutionCode），无法读取链上服务城市");
        }
        InstitutionKey key;
        try {
            key = institutionKeyService.findByCode(code);
        } catch (Exception e) {
            return CityLookup.unreadable(describe(e));
        }
        if (key == null || key.getAddress() == null || key.getAddress().trim().isEmpty()) {
            return CityLookup.unreadable("机构编码 " + code + " 未登记链上地址");
        }
        return lookupByAddress(key.getAddress());
    }

    /**
     * 该机构编码对应的<b>链上地址</b>（社保局自己的地址）；没有编码 / 未登记 / 地址为空时返回 null。
     *
     * <p>与 {@link #lookupByInstitutionCode(String)} 走的是同一条"编码 → 机构密钥 → 地址"链路，
     * 这里只把地址单独暴露出来（注资接口要用它记日志、并把"本局是谁"写清楚）。
     */
    public String addressOfInstitutionCode(String institutionCode) {
        String code = institutionCode == null ? null : institutionCode.trim();
        if (code == null || code.isEmpty()) {
            return null;
        }
        InstitutionKey key;
        try {
            key = institutionKeyService.findByCode(code);
        } catch (Exception e) {
            return null;
        }
        if (key == null || key.getAddress() == null) {
            return null;
        }
        String address = key.getAddress().trim();
        return address.isEmpty() ? null : address;
    }

    /** 按链上地址读该社保局的服务城市。 */
    public CityLookup lookupByAddress(String address) {
        String normalized = address == null ? null : address.trim().toLowerCase();
        String key = "chain:bureau-cities:" + normalized;
        Optional<Object> cached = cache.get(key);
        if (cached.isPresent() && cached.get() instanceof Map) {
            return fromCached((Map<?, ?>) cached.get());
        }
        try {
            CallResult result = chainTxService.call(TRANSFER, "getBureau",
                    ChainParams.address(address, "社保局地址"));
            Object value = ChainViewMapper.map(result, ChainViewMapper.BUREAU).get("cities");
            CityLookup lookup = CityLookup.readable(toStringList(value));
            cache.put(key, toCached(lookup), Duration.ofSeconds(30));
            return lookup;
        } catch (Exception e) {
            CityLookup lookup = CityLookup.unreadable(describe(e));
            cache.put(key, toCached(lookup), Duration.ofSeconds(5));
            return lookup;
        }
    }

    private static Map<String, Object> toCached(CityLookup lookup) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("cities", lookup.getCities());
        view.put("readable", lookup.isReadable());
        view.put("failureReason", lookup.getFailureReason());
        return view;
    }

    private static CityLookup fromCached(Map<?, ?> view) {
        List<String> cities = new ArrayList<>();
        Object rawCities = view.get("cities");
        if (rawCities instanceof List) {
            for (Object city : (List<?>) rawCities) {
                if (city != null) {
                    cities.add(String.valueOf(city));
                }
            }
        }
        boolean readable = Boolean.TRUE.equals(view.get("readable"));
        Object rawReason = view.get("failureReason");
        String reason = rawReason == null ? "" : String.valueOf(rawReason);
        return readable ? CityLookup.readable(cities) : CityLookup.unreadable(reason);
    }

    /**
     * {@code GET /api/bureau/cities} 用：当前登录社保局自己的服务城市。
     *
     * <p>读不到（链未就绪 / 未注册 / 无机构私钥）一律返回空数组，只记一条 warn——
     * 契约要求 200 + 空数组，不是 500。
     */
    public List<String> citiesOfInstitutionCode(String institutionCode) {
        CityLookup lookup = lookupByInstitutionCode(institutionCode);
        if (!lookup.isReadable()) {
            log.warn("读取机构 {} 的链上服务城市失败，按空数组返回：{}", institutionCode, lookup.getFailureReason());
        }
        return lookup.getCities();
    }

    // ------------------------------------------------------------------
    // 城市的占用者（社保局与城市一对一）
    // ------------------------------------------------------------------

    /**
     * 一个城市的占用查询结果。
     *
     * <p>{@code readable=false} 表示这次<b>没读到链</b>（链未就绪 / 合约未配置 / 节点异常），
     * 与"读到了、城市空闲"必须区分：调用方据此决定"降级放行"还是"确定可以占用"。
     */
    public static final class CityOccupant {

        private final boolean readable;
        private final String city;
        private final String bureau;
        private final String bureauName;

        private CityOccupant(boolean readable, String city, String bureau, String bureauName) {
            this.readable = readable;
            this.city = city;
            this.bureau = bureau == null ? "" : bureau;
            this.bureauName = bureauName == null ? "" : bureauName;
        }

        public boolean isReadable() {
            return readable;
        }

        /** 城市已被某个社保局绑定。 */
        public boolean isOccupied() {
            return !bureau.isEmpty();
        }

        public String getCity() {
            return city;
        }

        public String getBureau() {
            return bureau;
        }

        public String getBureauName() {
            return bureauName;
        }

        /** 给人看的占用者名字：优先链上机构名，读不到时退回地址。 */
        public String displayName() {
            return bureauName.isEmpty() ? bureau : bureauName;
        }
    }

    /**
     * 查一个城市当前绑定的社保局（合约 {@code bureauOfCity}）。
     *
     * <p>用于"开社保局账号"前的预检：一个城市只能绑一个社保局，提前查出来就能在<b>建号之前</b>
     * 拦下，而不是等 {@code addBureaus} 在链上 {@code CityAlreadyBound} 回滚、留下半截账号。
     *
     * <p>读链失败不抛异常：返回 {@code readable=false}，由调用方决定是否降级放行。
     */
    public CityOccupant occupantOfCity(String rawCity) {
        String city = rawCity == null ? "" : rawCity.trim();
        if (city.isEmpty()) {
            return new CityOccupant(true, "", "", "");
        }
        try {
            CallResult result = chainTxService.call(TRANSFER, "bureauOfCity",
                    ChainParams.text(city, "城市", true));
            Object value = result == null ? null : result.first();
            String address = value == null ? "" : String.valueOf(value).trim();
            if (isZeroAddress(address)) {
                return new CityOccupant(true, city, "", "");
            }
            return new CityOccupant(true, city, address, nameOfBureau(address));
        } catch (Exception e) {
            log.warn("读取城市「{}」绑定的社保局失败（跳过占用预检）：{}", city, describe(e));
            return new CityOccupant(false, city, "", "");
        }
    }

    /** 社保局的链上名称；读不到返回空串（不影响占用判定）。 */
    private String nameOfBureau(String address) {
        try {
            Object value = ChainViewMapper.map(chainTxService.call(TRANSFER, "getBureau",
                    ChainParams.address(address, "社保局地址")), ChainViewMapper.BUREAU).get("name");
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception e) {
            log.debug("读取社保局 {} 的名称失败（只用地址展示）：{}", address, describe(e));
            return "";
        }
    }

    private static boolean isZeroAddress(String address) {
        if (address == null || address.isEmpty()) {
            return true;
        }
        String hex = address.startsWith("0x") || address.startsWith("0X") ? address.substring(2) : address;
        if (hex.isEmpty()) {
            return true;
        }
        for (int i = 0; i < hex.length(); i++) {
            if (hex.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 全部已注册城市（ADMIN / 监管只读）
    // ------------------------------------------------------------------

    /**
     * {@code GET /api/admin/cities} 用：链上已注册的全部城市。
     *
     * <p>做法是 {@code getBureauList(0, 200)} 翻页拿到社保局地址，再逐个 {@code getBureau(address)}
     * 把它的 {@code cities} 摊平去重——合约没有"城市列表"接口，这是唯一的权威汇总方式。
     *
     * <p>链未就绪 / 单条查询失败都不抛异常：返回已经汇总到的部分（可能是空数组）。
     */
    public List<Map<String, Object>> allRegisteredCities() {
        // 城市 → 行；用 LinkedHashMap 既去重又保持链上顺序
        Map<String, Map<String, Object>> byCity = new LinkedHashMap<>();
        int offset = 0;
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                CallResult result = chainTxService.call(TRANSFER, "getBureauList",
                        ChainParams.uint(offset, "offset"), ChainParams.uint(BUREAU_PAGE_SIZE, "limit"));
                List<Object> values = result == null ? null : result.getValues();
                List<?> addresses = firstList(values);
                if (addresses.isEmpty()) {
                    break;
                }
                for (Object raw : addresses) {
                    String address = raw == null ? null : String.valueOf(raw).trim();
                    if (address == null || address.isEmpty()) {
                        continue;
                    }
                    addCitiesOfBureau(byCity, address);
                }
                int nextOffset = asInt(values != null && values.size() > 2 ? values.get(2) : null,
                        offset + addresses.size());
                if (nextOffset <= offset) {
                    // 合约没有推进 nextOffset：再翻就是死循环，停下
                    break;
                }
                offset = nextOffset;
            }
        } catch (Exception e) {
            log.warn("读取链上社保局名单 / 服务城市失败，返回已汇总的 {} 个城市：{}",
                    byCity.size(), describe(e));
        }
        return new ArrayList<>(byCity.values());
    }

    private void addCitiesOfBureau(Map<String, Map<String, Object>> byCity, String address) {
        Map<String, Object> bureau;
        try {
            bureau = ChainViewMapper.map(chainTxService.call(TRANSFER, "getBureau",
                    ChainParams.address(address, "社保局地址")), ChainViewMapper.BUREAU);
        } catch (Exception e) {
            // 名单里有、但单条读不出来（例如合约升级窗口）：跳过这一条，不影响其它社保局
            log.warn("读取社保局 {} 的链上信息失败，跳过：{}", address, describe(e));
            return;
        }
        String name = bureau.get("name") == null ? "" : String.valueOf(bureau.get("name")).trim();
        for (String city : toStringList(bureau.get("cities"))) {
            if (byCity.containsKey(city)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("city", city);
            row.put("bureau", address);
            row.put("bureauName", name);
            byCity.put(city, row);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 把合约返回的 {@code string[]}（ABI 解码后是 List）归一化成去空白的字符串列表。 */
    private static List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) value) {
            String city = item == null ? null : String.valueOf(item).trim();
            if (city != null && !city.isEmpty()) {
                result.add(city);
            }
        }
        return result;
    }

    /** 取合约返回值的第一个 List（例如 {@code getBureauList} 的 address[]）。 */
    private static List<?> firstList(List<Object> values) {
        if (values == null || values.isEmpty() || !(values.get(0) instanceof List)) {
            return Collections.emptyList();
        }
        return (List<?>) values.get(0);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 落到 fallback
            }
        }
        return fallback;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        return e.getClass().getSimpleName();
    }
}
