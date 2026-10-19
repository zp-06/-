package com.hmall.item.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品变更消息：业务写库后发送到 MQ，消费者根据 opType 执行对应的 ES 同步操作。
 * opType 取值见 {@link com.hmall.common.constants.ItemEsConstants}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemEsMessage {
    /** 操作类型：save / update / stock / delete */
    private String opType;
    /** 商品id */
    private Long id;
}
