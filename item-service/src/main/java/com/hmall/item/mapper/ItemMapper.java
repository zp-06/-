package com.hmall.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.hmall.item.domain.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 商品表 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface ItemMapper extends BaseMapper<Item> {

    /**
     * 乐观锁（CAS 条件更新）扣减库存：
     * 仅当剩余库存 >= 购买数量时才扣减，返回影响行数；返回 0 表示库存不足
     */
    @Update("UPDATE item SET stock = stock - #{num} WHERE id = #{itemId} AND stock >= #{num}")
    int updateStock(OrderDetailDTO orderDetail);
}
