package com.gxcj.xjtool.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnixPathSupportTest {

    @Test
    void normalizesTerminalPathsWithoutChangingUnixSemantics() {
        assertEquals("/root", UnixPathSupport.defaultHome("root"));
        assertEquals("/home/app", UnixPathSupport.defaultHome("app"));
        assertEquals("deploy.sh", UnixPathSupport.basename("/opt/scripts/deploy.sh"));
        assertEquals("/opt/release", UnixPathSupport.normalize("/opt/app/../release/./"));
        assertEquals("/opt", UnixPathSupport.parent("/opt/release"));
        assertEquals("/", UnixPathSupport.parent("/opt"));
    }
}
