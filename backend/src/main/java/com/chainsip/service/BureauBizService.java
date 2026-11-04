package com.chainsip.service;

import com.chainsip.chain.dto.SendResult;
import com.chainsip.common.BizException;
import com.chainsip.common.ContractErrorMessages;
import com.chainsip.common.ErrorCode;
import com.chainsip.config.ChainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * 社保局（BUREAU）侧的业务写操作：目前只有"给本局下辖的缴费单位注资"。
 *
 * <p><b>为什么由后端代签、且必须用 ADMIN 私钥</b>：合约里 {@code depositSponsor} 带 {@code onlyAdmin}，
 * 社保局自己发这笔交易必然被回滚（{@code NotAnAdmin}）。因此这里沿用与
 * {@code AdminBizService#addSponsorAccountAsAdmin} 同一套做法：链下先把关"这笔注资该不该做"，
 * 通过后再用 ADMIN 身份代签。
 *
 * <p><b>授权边界在这里</b>：注资对象必须是"本局服务城市里的缴费单位"。判据是该单位链上记录的
 * {@code city} 落在本局链上 {@code getBureau(address).cities} 里 —— 两者都以链上为权威，
 * 链下不另存一份归属关系。不合规直接 403，<b>不调链</b>。
 */
@Service
public class BureauBizService {

    private static final Logger log = LoggerFactory.getLogger(BureauBizService.class);

    private static final String CORE = ChainProperties.CORE;

    private final ChainTxService chain;
    private final BureauCityService bureauCityService;

    public BureauBizService(ChainTxService chain, BureauCityService bureauCityService) {
        this.chain = chain;
        this.bureauCityService = bureauCityService;
    }

    /**
     * 用 ADMIN 身份代签 {@code ChainSIP#depositSponsor(sponsor, amount)}，给本局下辖的缴费单位注资。
     *
     * <p>执行顺序（本地校验 → 读链判归属 → 代签）：
     * <ol>
     *   <li>地址格式与金额（字符串 → 非负整数，且必须 &gt; 0）——纯本地校验，不碰链；</li>
     *   <li>取本局自己的链上地址与服务城市（{@link BureauCityService}，与 {@code GET /api/bureau/cities} 同链路）；</li>
     *   <li>读该单位的链上记录拿 {@code city}；不存在（合约 {@code UnknownSponsor}）→ 400 并带上真实原因；</li>
     *   <li>校验 {@code 单位.city ∈ 本局服务城市}；不通过 → 403，<b>不发交易</b>；</li>
     *   <li>通过 → {@code sendAs(ADMIN, ...)} 代签，并记一条含 txHash 的日志（金额不是秘密，可以记）。</li>
     * </ol>
     */
    public SendResult depositToOwnSponsor(String sponsor, String amount) {
        // ---- 1. 本地校验：地址格式 + 金额 ----
        String sponsorAddress = ChainParams.address(sponsor, "缴费单位地址");
        BigInteger value = ChainParams.uint(amount, "注资金额");
        if (value.signum() == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "注资金额必须大于 0");
        }

        // ---- 2. 本局是谁：institutionCode → 地址 + 服务城市 ----
        String institutionCode = chain.currentInstitutionCode();
        String bureauAddress = bureauCityService.addressOfInstitutionCode(institutionCode);
        BureauCityService.CityLookup lookup = bureauCityService.lookupByInstitutionCode(institutionCode);
        if (!lookup.isReadable()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "读取本社保局的链上服务城市失败，无法判断该缴费单位是否属于本局："
                            + ContractErrorMessages.friendly(lookup.getFailureReason(), "链上读取失败，请稍后重试"));
        }
        List<String> cities = lookup.getCities();
        if (cities.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "本社保局尚未在链上授权任何服务城市，无法为缴费单位注资（请先联系平台管理员为"
                            + (bureauAddress == null ? institutionCode : bureauAddress) + " 授权服务城市）");
        }

        // ---- 3. 目标单位的链上记录（拿 city）；UnknownSponsor → 400 ----
        String city = sponsorCity(sponsorAddress);

        // ---- 4. 授权边界：单位城市必须属于本局服务城市（不通过就不调链） ----
        if (city == null || !cities.contains(city)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "该缴费单位（城市 " + (city == null ? "未知" : city) + "）不属于本社保局（服务城市："
                            + String.join("、", cities) + "）");
        }

        // ---- 5. ADMIN 代签 ----
        SendResult result = chain.sendAs(AuthService.ROLE_ADMIN, CORE, "depositSponsor", sponsorAddress, value);
        log.info("社保局 {}（{}）为下辖缴费单位 {}（城市 {}）注资 {} 成功：txHash={}",
                institutionCode, bureauAddress, sponsorAddress, city, value, result.getTransactionHash());
        return result;
    }

    /**
     * 读缴费单位的链上记录拿 {@code city}；单位未注册（合约 UnknownSponsor）时给出带真实原因的 400。
     *
     * <p><b>必须用 ADMIN 身份读</b>：合约 {@code getSponsorAccount} 只允许「单位自己 / ADMIN / REGULATOR」
     * 读，社保局（BUREAU）直接用当前登录身份读会被 {@code SponsorNotVisible} 回滚 —— 这是实测踩到的坑，
     * 所以这里走 {@link ChainTxService#callAs}，与随后代签 {@code depositSponsor} 的身份保持一致。
     */
    private String sponsorCity(String sponsorAddress) {
        Map<String, Object> account;
        try {
            account = ChainViewMapper.mapStruct(
                    chain.callAs(AuthService.ROLE_ADMIN, CORE, "getSponsorAccount", sponsorAddress),
                    ChainViewMapper.SPONSOR_ACCOUNT);
        } catch (BizException e) {
            if (e.getErrorCode() == ErrorCode.CHAIN_CALL_ERROR) {
                // 合约对未注册单位 require(s.exists, "UnknownSponsor")；原文只进日志，界面只给中文
                log.warn("读取缴费单位 {} 的链上记录失败：{}", sponsorAddress, e.getMessage());
                throw new BizException(ErrorCode.BAD_REQUEST,
                        ContractErrorMessages.friendly(e.getMessage(),
                                "无法读取该缴费单位的链上信息，请稍后重试"));
            }
            throw e;
        }
        Object city = account.get("city");
        if (city == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该缴费单位未在链上注册（链上记录里没有城市信息）");
        }
        String trimmed = String.valueOf(city).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
