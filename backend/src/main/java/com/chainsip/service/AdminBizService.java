package com.chainsip.service;

import com.chainsip.chain.dto.CallResult;
import com.chainsip.chain.dto.SendResult;
import com.chainsip.config.ChainProperties;
import com.chainsip.dto.PersonRegisterRequest;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ADMIN 侧业务：平台初始化（公司、社保局、监管账户）、注资、全量建档、注销、机构接线。
 *
 * <p>所有写操作都会走 {@link ChainTxService}，用 ADMIN 机构私钥签名——
 * 合约里对应函数都是 {@code onlyAdmin}，换任何别的私钥都会被合约回滚。
 */
@Service
public class AdminBizService {

    private static final String CORE = ChainProperties.CORE;
    private static final String TRANSFER = ChainProperties.TRANSFER;

    private final ChainTxService chain;

    public AdminBizService(ChainTxService chain) {
        this.chain = chain;
    }

    // ------------------------------------------------------------------
    // 核心合约：公司 / 注资 / 建档 / 注销 / 监管
    // ------------------------------------------------------------------

    public SendResult addSponsorAccount(String sponsor, String name, String city) {
        return chain.send(chain.currentInstitutionCode(), CORE, "addSponsorAccount", sponsor, name, city);
    }

    /**
     * 由后端以 <b>ADMIN 身份代签</b>注册缴费单位（"创建即上链"用）。
     *
     * <p>与 {@link #addSponsorAccount} 的区别：那个用"当前登录账号"的私钥签名（只适合 ADMIN 自己调用），
     * 本方法固定用 ADMIN 机构私钥——社保局创建缴费单位时正是这个场景：合约里
     * {@code addSponsorAccount} 带 {@code onlyAdmin}，若用发起创建的社保局私钥签名必然回滚。
     */
    public SendResult addSponsorAccountAsAdmin(String sponsor, String name, String city) {
        return chain.sendAs(AuthService.ROLE_ADMIN, CORE, "addSponsorAccount", sponsor, name, city);
    }

    public SendResult depositSponsor(String sponsor, String amount) {
        return chain.send(chain.currentInstitutionCode(), CORE, "depositSponsor",
                sponsor, ChainParams.uint(amount, "注资金额"));
    }

    public SendResult addPerson(PersonRegisterRequest request) {
        return chain.send(chain.currentInstitutionCode(), CORE, "addPerson",
                ChainParams.address(request.getEmployee(), "职工地址"),
                request.getName(),
                ChainParams.uint(request.getAge(), "年龄"),
                request.getIdNumber(),
                request.getEmployer(),
                ChainParams.uint(request.getStartDate(), "入职日期"),
                ChainParams.uint(request.getSalary(), "月工资"),
                ChainParams.address(request.getSponsor(), "缴费单位地址"));
    }

    public SendResult removeEmployee(String employee) {
        return chain.send(chain.currentInstitutionCode(), CORE, "removeEmployee",
                ChainParams.address(employee, "职工地址"));
    }

    public SendResult setTransferContract(String address) {
        return chain.send(chain.currentInstitutionCode(), CORE, "setTransferContract",
                ChainParams.address(address, "转移合约地址"));
    }

    public SendResult grantBureauRole(String bureau) {
        return chain.send(chain.currentInstitutionCode(), CORE, "grantBureauRole",
                ChainParams.address(bureau, "社保局地址"));
    }

    public SendResult addRegulatorAccount(String regulator, String name, String agency) {
        return chain.send(chain.currentInstitutionCode(), CORE, "addRegulatorAccount",
                ChainParams.address(regulator, "监管地址"), name, agency);
    }

    public SendResult removeRegulatorAccount(String regulator) {
        return chain.send(chain.currentInstitutionCode(), CORE, "removeRegulatorAccount",
                ChainParams.address(regulator, "监管地址"));
    }

    // ------------------------------------------------------------------
    // 转移合约：社保局注册与授权（这些函数在转移合约上，但同样只允许 ADMIN 调用）
    // ------------------------------------------------------------------

    public SendResult addBureaus(String bureau, String name, String city) {
        return chain.send(chain.currentInstitutionCode(), TRANSFER, "addBureaus",
                ChainParams.address(bureau, "社保局地址"), name, city);
    }

    public SendResult changeBureausAuthorized(String bureau, boolean authorized) {
        return chain.send(chain.currentInstitutionCode(), TRANSFER, "changeBureausAuthorized",
                ChainParams.address(bureau, "社保局地址"), authorized);
    }

    // ------------------------------------------------------------------
    // 只读：对账 / 审计
    // ------------------------------------------------------------------

    public Map<String, Object> getSponsorAccount(String sponsor) {
        CallResult result = chain.call(CORE, "getSponsorAccount", ChainParams.address(sponsor, "公司地址"));
        return ChainViewMapper.map(result, ChainViewMapper.SPONSOR_ACCOUNT);
    }

    /** 平台资金/权益总览。合约 {@code getSystemStats} 是「单个 tuple 输出」，故用 mapStruct 拆一层。 */
    public Map<String, Object> getSystemStats() {
        return ChainViewMapper.mapStruct(chain.call(CORE, "getSystemStats"), ChainViewMapper.SYSTEM_STATS);
    }

    public Object getSponsorCount() {
        return chain.call(CORE, "getSponsorCount").first();
    }
}
