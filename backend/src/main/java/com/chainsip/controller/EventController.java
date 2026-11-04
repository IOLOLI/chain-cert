package com.chainsip.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chainsip.common.ApiResult;
import com.chainsip.entity.ChainEvent;
import com.chainsip.entity.ChainTxLog;
import com.chainsip.mapper.ChainEventMapper;
import com.chainsip.mapper.ChainTxLogMapper;
import com.chainsip.security.RequireRole;
import com.chainsip.service.RedisCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.time.Duration;

/**
 * 链下索引查询：链上事件索引与上链操作日志。
 *
 * <p>合约刻意没有"遍历所有职工/公司"的接口（避免链上循环与 gas 爆炸），
 * 名单、报表、模糊查询都走这里；链上余额与状态始终是唯一真相，本表可随时重放事件重建。
 *
 * <p><b>链上索引为审计/监管用途，仅 ADMIN 与 REGULATOR 可访问</b>：这两张表里带机构编码、
 * 发送地址与交易参数，属于全平台视角的运营/审计数据，不适合缴费单位或职工查看，
 * 因此整个 controller 用类级 {@link RequireRole} 统一收口。
 */
@Tag(name = "08 链下索引", description = "链上事件索引 / 上链操作日志（分页查询，仅 ADMIN / REGULATOR）")
@RestController
@RequestMapping("/api/index")
@RequireRole({"ADMIN", "REGULATOR"})
public class EventController {

    private final ChainEventMapper eventMapper;
    private final ChainTxLogMapper txLogMapper;
    private final RedisCacheService cache;

    public EventController(ChainEventMapper eventMapper, ChainTxLogMapper txLogMapper,
                           RedisCacheService cache) {
        this.eventMapper = eventMapper;
        this.txLogMapper = txLogMapper;
        this.cache = cache;
    }

    @Operation(summary = "链上事件索引（可按职工、单位、事件名过滤）")
    @GetMapping("/events")
    public ApiResult<Map<String, Object>> events(@RequestParam(required = false) String employee,
                                                 @RequestParam(required = false) String sponsor,
                                                 @RequestParam(required = false) String eventName,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        if (page >= 1 && page <= 3 && size > 0 && size <= 100) {
            String key = "index:events:" + text(employee) + ":" + text(sponsor) + ":"
                    + text(eventName) + ":" + page + ":" + size;
            return ApiResult.ok(cache.getOrLoad(key, Duration.ofSeconds(15),
                    () -> queryEvents(employee, sponsor, eventName, page, size)));
        }
        return ApiResult.ok(queryEvents(employee, sponsor, eventName, page, size));
    }

    private Map<String, Object> queryEvents(String employee, String sponsor, String eventName,
                                            int page, int size) {
        LambdaQueryWrapper<ChainEvent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(employee)) {
            wrapper.eq(ChainEvent::getEmployeeAddress, employee.toLowerCase());
        }
        if (StringUtils.hasText(sponsor)) {
            wrapper.eq(ChainEvent::getSponsorAddress, sponsor.toLowerCase());
        }
        if (StringUtils.hasText(eventName)) {
            wrapper.eq(ChainEvent::getEventName, eventName);
        }
        wrapper.orderByDesc(ChainEvent::getId);
        Page<ChainEvent> result = eventMapper.selectPage(new Page<>(page, size), wrapper);
        return pageView(result);
    }

    @Operation(summary = "上链操作日志（可按机构、函数、成功与否过滤）")
    @GetMapping("/tx-logs")
    public ApiResult<Map<String, Object>> txLogs(@RequestParam(required = false) String institutionCode,
                                                 @RequestParam(required = false) String functionName,
                                                 @RequestParam(required = false) Boolean success,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        if (page >= 1 && page <= 3 && size > 0 && size <= 100) {
            String key = "index:tx-logs:" + text(institutionCode) + ":" + text(functionName)
                    + ":" + (success == null ? "*" : success) + ":" + page + ":" + size;
            return ApiResult.ok(cache.getOrLoad(key, Duration.ofSeconds(15),
                    () -> queryTxLogs(institutionCode, functionName, success, page, size)));
        }
        return ApiResult.ok(queryTxLogs(institutionCode, functionName, success, page, size));
    }

    private Map<String, Object> queryTxLogs(String institutionCode, String functionName,
                                            Boolean success, int page, int size) {
        LambdaQueryWrapper<ChainTxLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(institutionCode)) {
            wrapper.eq(ChainTxLog::getInstitutionCode, institutionCode);
        }
        if (StringUtils.hasText(functionName)) {
            wrapper.eq(ChainTxLog::getFunctionName, functionName);
        }
        if (success != null) {
            wrapper.eq(ChainTxLog::getSuccess, success);
        }
        wrapper.orderByDesc(ChainTxLog::getId);
        Page<ChainTxLog> result = txLogMapper.selectPage(new Page<>(page, size), wrapper);
        return pageView(result);
    }

    private Map<String, Object> pageView(Page<?> page) {
        Map<String, Object> data = new HashMap<>();
        data.put("records", page.getRecords());
        data.put("total", page.getTotal());
        data.put("page", page.getCurrent());
        data.put("size", page.getSize());
        data.put("pages", page.getPages());
        return data;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
