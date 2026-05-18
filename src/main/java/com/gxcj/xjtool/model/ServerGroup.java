package com.gxcj.xjtool.model;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

@Data
public class ServerGroup {
    @NotBlank(message = "分组名称不能为空")
    @Size(max = 100, message = "分组名称长度不能超过100")
    private String name;

    private List<ServerInfo> servers = new ArrayList<>();
}
