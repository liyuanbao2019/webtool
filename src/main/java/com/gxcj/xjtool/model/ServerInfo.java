package com.gxcj.xjtool.model;

import lombok.Data;

import javax.validation.constraints.*;

@Data
public class ServerInfo {
    private String id;

    /** 分组名称 */
    private String groupName;

    @NotBlank(message = "服务器名称不能为空")
    @Size(max = 100, message = "服务器名称长度不能超过100")
    private String name;

    @NotBlank(message = "主机地址不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9.-]+$", message = "主机地址格式不正确，只能包含字母、数字、点和横杠")
    private String host;

    @Min(value = 1, message = "端口号必须在1-65535之间")
    @Max(value = 65535, message = "端口号必须在1-65535之间")
    private int port = 22;

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过50")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 1, max = 200, message = "密码长度不能超过200")
    private String password;

    private String connectionMode = "ssh";

    private String agentBaseUrl;

    private String agentId;

    private String agentToken;

    /**
     * sudo/su 用户名及密码映射表
     * Key: sudo/su 用户名 (小写)，Value: 对应密码
     * 用户在终端输入 "su - username" 或 "sudo -u username" 时自动填充密码
     */
    private java.util.Map<String, String> suPasswords = new java.util.LinkedHashMap<>();
}
