package com.hmall.item.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.common.domain.PageDTO;

import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.domain.query.ItemPageQuery;
import com.hmall.item.service.IItemService;
import com.hmall.item.service.ItemEsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Api(tags = "搜索相关接口")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final IItemService itemService;
    private final ItemEsService itemEsService;

    @ApiOperation("搜索商品")
    @GetMapping("/list")
    public PageDTO<ItemDTO> search(ItemPageQuery query,
                                   @RequestParam(value = "engine", defaultValue = "es") String engine) {
        // engine=mysql：走数据库检索（用于性能对比/手动指定）；默认走Elasticsearch检索
        if ("mysql".equalsIgnoreCase(engine)) {
            return searchByMysql(query);
        }
        try {
            return itemEsService.search(query);
        } catch (Exception e) {
            // ES不可用/检索异常：降级MySQL检索，保证接口可用
            log.error("ES检索失败，降级MySQL检索, key={}", query.getKey(), e);
            return searchByMysql(query);
        }
    }

    /**
     * MySQL 检索（MyBatis-Plus like/eq + 分页），作为ES的降级方案与性能对比基线。
     */
    private PageDTO<ItemDTO> searchByMysql(ItemPageQuery query) {
        // 分页查询
        Page<Item> result = itemService.lambdaQuery()
                .like(StrUtil.isNotBlank(query.getKey()), Item::getName, query.getKey())
                .eq(StrUtil.isNotBlank(query.getBrand()), Item::getBrand, query.getBrand())
                .eq(StrUtil.isNotBlank(query.getCategory()), Item::getCategory, query.getCategory())
                .eq(Item::getStatus, 1)
                .between(query.getMaxPrice() != null, Item::getPrice, query.getMinPrice(), query.getMaxPrice())
                .page(query.toMpPage("update_time", false));
        // 封装并返回
        return PageDTO.of(result, ItemDTO.class);
    }
}
