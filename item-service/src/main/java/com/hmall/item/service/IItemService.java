package com.hmall.item.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;


import java.util.Collection;
import java.util.List;

/** 商品表 服务类 */
public interface IItemService extends IService<Item> {

    void deductStock(List<OrderDetailDTO> items);

    /**
     * 回复库存（取消订单、退回商品时调用）
     * @param items 商品id与数量的集合
     */
    void refundStock(List<OrderDetailDTO> items);

    List<ItemDTO> queryItemByIds(Collection<Long> ids);

    ItemDTO queryItemById(Long id);
}
