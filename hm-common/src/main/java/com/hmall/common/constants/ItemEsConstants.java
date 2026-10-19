package com.hmall.common.constants;

/**
 * 商品搜索(Elasticsearch)相关常量。
 */
public class ItemEsConstants {

    /** 商品索引名 */
    public static final String ITEM_INDEX = "hmall-item";

    /* ============ 索引字段名（与 Item 实体一致，驼峰命名） ============ */
    public static final String FIELD_ID = "id";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_PRICE = "price";
    public static final String FIELD_STOCK = "stock";
    public static final String FIELD_SOLD = "sold";
    public static final String FIELD_IMAGE = "image";
    public static final String FIELD_CATEGORY = "category";
    public static final String FIELD_BRAND = "brand";
    public static final String FIELD_SPEC = "spec";
    public static final String FIELD_COMMENT_COUNT = "commentCount";
    public static final String FIELD_IS_AD = "isAD";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_CREATE_TIME = "createTime";
    public static final String FIELD_UPDATE_TIME = "updateTime";

    /** 全量同步每批条数 */
    public static final int SYNC_BATCH_SIZE = 500;

    /** ES中date字段格式 */
    public static final String ES_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /* ============ 商品变更消息操作类型（消费者按类型执行不同ES操作） ============ */
    /** 新增/修改/库存变化：覆盖写入文档（IndexRequest id 相同则更新） */
    public static final String ES_OP_SAVE = "save";
    public static final String ES_OP_UPDATE = "update";
    public static final String ES_OP_STOCK = "stock";
    /** 删除：删除ES文档 */
    public static final String ES_OP_DELETE = "delete";
}
