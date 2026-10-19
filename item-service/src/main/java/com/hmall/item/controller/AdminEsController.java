package com.hmall.item.controller;

import com.hmall.item.service.ItemEsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * ES 索引管理接口（运维/测试用）：
 * 全量同步、重建索引，幂等可重复调用。
 */
@Api(tags = "ES索引管理接口")
@RestController
@RequestMapping("/admin/es")
@RequiredArgsConstructor
public class AdminEsController {

    private final ItemEsService itemEsService;

    @ApiOperation("全量同步商品数据到ES")
    @PostMapping("/sync")
    public long sync() {
        return itemEsService.syncAll();
    }

    @ApiOperation("重建商品索引并全量同步")
    @PostMapping("/rebuild")
    public long rebuild() throws IOException {
        return itemEsService.rebuildIndex();
    }
}
