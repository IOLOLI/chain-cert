package com.chainsip;

import com.chainsip.policy.entity.PolicyContext;
import com.chainsip.policy.service.PolicyChainService;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 政策参数打包与合约默认值的一致性测试。
 *
 * <p>位布局必须与 contracts/ChainSIP.sol 的 DEFAULT_POLICY 完全一致；这里把默认参数打包后
 * 与合约里的常量逐位比对，任何一边改了布局都会在这里失败。
 */
class PolicyChainPackingTest {

    /** 与合约 DEFAULT_POLICY 相同：800 | 1900<<16 | 100<<32 | 139<<48 | 60<<64 | 180<<80 | 30<<96 | 3200<<128 | 24000<<176。 */
    private static final BigInteger CONTRACT_DEFAULT = BigInteger.valueOf(800)
            .or(BigInteger.valueOf(1900).shiftLeft(16))
            .or(BigInteger.valueOf(100).shiftLeft(32))
            .or(BigInteger.valueOf(139).shiftLeft(48))
            .or(BigInteger.valueOf(60).shiftLeft(64))
            .or(BigInteger.valueOf(180).shiftLeft(80))
            .or(BigInteger.valueOf(30).shiftLeft(96))
            .or(BigInteger.valueOf(3200).shiftLeft(128))
            .or(BigInteger.valueOf(24000).shiftLeft(176));

    private final PolicyChainService service = new PolicyChainService(null, null);

    @Test
    void defaultContextPacksToContractDefault() {
        PolicyContext context = PolicyContext.builder().build();
        assertEquals(CONTRACT_DEFAULT, service.pack(context));
    }

    @Test
    void unpackRoundTripsToSameValues() {
        PolicyContext context = PolicyContext.builder().build();
        Map<String, String> fromContext = service.contextValues(context);
        Map<String, String> fromChain = service.unpack(service.pack(context));
        assertEquals(fromContext, fromChain);
        assertTrue(fromChain.containsKey("contrib.employee.rate"));
        assertEquals("0.08", fromChain.get("contrib.employee.rate"));
        assertEquals("0.19", fromChain.get("contrib.employer.rate"));
        assertEquals("3200", fromChain.get("contrib.base.floor.value"));
        assertEquals("24000", fromChain.get("contrib.base.ceiling.value"));
        assertEquals("180", fromChain.get("qual.min.months"));
        assertEquals("60", fromChain.get("retire.age.male"));
        assertEquals("0.01", fromChain.get("benefit.basic.rate.yearly"));
        assertEquals("{\"60\":139}", fromChain.get("benefit.payment.months.table"));
        assertEquals("30", fromChain.get("benefit.pension.cycle.days"));
    }

    @Test
    void changedRateChangesPackedBits() {
        PolicyContext context = PolicyContext.builder()
                .employeeContribRate(new java.math.BigDecimal("0.09"))
                .build();
        BigInteger packed = service.pack(context);
        // 低 16 位 = 个人费率 bps
        assertEquals(900L, packed.and(BigInteger.valueOf(0xFFFFL)).longValue());
        assertEquals("0.09", service.unpack(packed).get("contrib.employee.rate"));
    }
}
