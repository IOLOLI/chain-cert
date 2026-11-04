package com.chainsip.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * ADMIN 全量录入职工档案的请求体。字段顺序与合约 addPerson 一致。
 */
@Data
public class PersonRegisterRequest {

    @NotBlank(message = "职工地址不能为空")
    private String employee;

    @NotBlank(message = "姓名不能为空")
    private String name;

    @NotNull(message = "年龄不能为空")
    private Integer age;

    @NotBlank(message = "身份证号不能为空")
    private String idNumber;

    /** 劳动部门档案里的用工单位名称（可与企业名一致） */
    private String employer;

    /** 入职日期（Unix 秒） */
    @NotNull(message = "入职日期不能为空")
    private Long startDate;

    /** 月工资（元） */
    @NotNull(message = "月工资不能为空")
    private Long salary;

    @NotBlank(message = "缴费单位地址不能为空")
    private String sponsor;
}
