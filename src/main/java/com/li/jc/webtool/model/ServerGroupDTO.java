package com.li.jc.webtool.model;

import lombok.Data;

/**
 * 服务器分组DTO
 * 分组名称明文传输，服务器信息整体加密传输
 */
@Data
public class ServerGroupDTO {
    private String name;
    private java.util.List<ServerInfoDTO> servers;
}
