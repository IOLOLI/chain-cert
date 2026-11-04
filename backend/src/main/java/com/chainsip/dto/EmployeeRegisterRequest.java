package com.chainsip.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 单位自助为职工建档的请求体。字段顺序与合约 addEmployee 一致（缴费单位固定为调用者）。
 */
@Data
public class EmployeeRegisterRequest {

    @NotBlank(message = "职工地址不能为空")
    private String employee;

    @NotBlank(message = "姓名不能为空")
    private String name;

    @NotNull(message = "年龄不能为空")
    private Integer age;

    @NotBlank(message = "身份证号不能为空")
    private String idNumber;

    private String employer;

    @NotNull(message = "入职日期不能为空")
    private Long startDate;

    @NotNull(message = "月工资不能为空")
    private Long salary;
}
