package com.hmall.item.service;

import com.hmall.common.domain.PageDTO;
import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.query.ItemPageQuery;

import java.io.IOException;

/**
 * 商品 Elasticsearch 检索与数据同步服务。
 */
public interface ItemEsService {

    /**
     * 创建商品索引（幂等：已存在则跳过）。
     */
    void ensureIndex() throws IOException;

    /**
     * 重建索引：删除旧索引 -> 重建 -> 全量同步。
     */
    long rebuildIndex() throws IOException;

    /**
     * 全量同步：分页读取 MySQL 全部商品写入 ES。
     *
     * @return 同步条数
     */
    long syncAll();

    /**
     * 单条同步（新增/修改商品后调用）；商品不存在则删除ES文档。
     */
    void syncItem(Long id);

    /**
     * 删除ES文档（商品删除/下架时调用）。
     */
    void deleteItem(Long id);

    /**
     * ES检索商品（组合条件：关键字match + 品牌/分类/状态term + 价格range + 更新时间倒序分页）。
     */
    PageDTO<ItemDTO> search(ItemPageQuery query) throws IOException;
}
