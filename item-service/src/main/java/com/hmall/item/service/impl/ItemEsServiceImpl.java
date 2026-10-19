package com.hmall.item.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.domain.query.ItemPageQuery;
import com.hmall.item.mapper.ItemMapper;
import com.hmall.item.service.ItemEsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmall.common.constants.ItemEsConstants.*;

/**
 * 商品ES检索与数据同步实现：
 * 1. 启动时幂等创建索引，索引为空则自动全量同步MySQL商品数据；
 * 2. 提供全量/单条同步，供新增、修改、删除商品后保持ES与MySQL一致；
 * 3. 检索：商品名match(IK分词) + 品牌/分类/状态term过滤 + 价格range + 更新时间倒序分页。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemEsServiceImpl implements ItemEsService, ApplicationRunner {

    private final ItemMapper itemMapper;
    private final RestHighLevelClient client;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(ES_DATE_FORMAT);

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureIndex();
            long esCount = countDocuments();
            long dbCount = itemMapper.selectCount(null);
            if (esCount == 0 && dbCount > 0) {
                log.info("ES商品索引为空(db={}条)，开始自动全量同步...", dbCount);
                long synced = syncAll();
                log.info("ES商品全量同步完成，共同步 {} 条", synced);
            } else {
                log.info("ES索引已存在文档 {} 条(db={}条)，跳过全量同步", esCount, dbCount);
            }
        } catch (Exception e) {
            log.error("ES索引初始化失败，请检查ES是否可用；搜索将降级为MySQL", e);
        }
    }

    @Override
    public void ensureIndex() throws IOException {
        GetIndexRequest getReq = new GetIndexRequest(ITEM_INDEX);
        if (client.indices().exists(getReq, RequestOptions.DEFAULT)) {
            return;
        }
        CreateIndexRequest req = new CreateIndexRequest(ITEM_INDEX);
        // 自定义分词器：写入用 ik_max_word（最大切分），查询用 ik_smart（智能切分）
        req.settings(Settings.builder()
                .put("analysis.analyzer.text_analyzer.type", "custom")
                .put("analysis.analyzer.text_analyzer.tokenizer", "ik_max_word")
                .put("analysis.analyzer.text_search_analyzer.type", "custom")
                .put("analysis.analyzer.text_search_analyzer.tokenizer", "ik_smart"));
        // mapping
        Map<String, Object> props = new HashMap<>();
        props.put(FIELD_ID, Map.of("type", "long"));
        props.put(FIELD_NAME, Map.of("type", "text",
                "analyzer", "text_analyzer", "search_analyzer", "text_search_analyzer"));
        props.put(FIELD_PRICE, Map.of("type", "long"));
        props.put(FIELD_STOCK, Map.of("type", "long"));
        props.put(FIELD_SOLD, Map.of("type", "long"));
        props.put(FIELD_IMAGE, Map.of("type", "keyword", "index", false));
        props.put(FIELD_CATEGORY, Map.of("type", "keyword"));
        props.put(FIELD_BRAND, Map.of("type", "keyword"));
        props.put(FIELD_SPEC, Map.of("type", "keyword", "index", false));
        props.put(FIELD_COMMENT_COUNT, Map.of("type", "long"));
        props.put(FIELD_IS_AD, Map.of("type", "boolean"));
        props.put(FIELD_STATUS, Map.of("type", "long"));
        props.put(FIELD_CREATE_TIME, Map.of("type", "date", "format", ES_DATE_FORMAT));
        props.put(FIELD_UPDATE_TIME, Map.of("type", "date", "format", ES_DATE_FORMAT));
        req.mapping(Map.of("properties", props));
        client.indices().create(req, RequestOptions.DEFAULT);
        log.info("ES索引 {} 创建成功", ITEM_INDEX);
    }

    @Override
    public long rebuildIndex() throws IOException {
        AcknowledgedResponse resp = client.indices().delete(new DeleteIndexRequest(ITEM_INDEX), RequestOptions.DEFAULT);
        log.info("ES索引 {} 删除{}", ITEM_INDEX, resp.isAcknowledged() ? "成功" : "失败");
        ensureIndex();
        return syncAll();
    }

    @Override
    public long syncAll() {
        long total = itemMapper.selectCount(null);
        long synced = 0;
        Page<Item> page = new Page<>(1, SYNC_BATCH_SIZE);
        while (true) {
            page = itemMapper.selectPage(page,
                    new LambdaQueryWrapper<Item>().orderByAsc(Item::getId));
            List<Item> records = page.getRecords();
            if (records.isEmpty()) {
                break;
            }
            bulkIndex(records);
            synced += records.size();
            log.info("ES全量同步进度：{}/{}", synced, total);
            if (page.getCurrent() * page.getSize() >= page.getTotal()) {
                break;
            }
            page.setCurrent(page.getCurrent() + 1);
        }
        return synced;
    }

    @Override
    public void syncItem(Long id) {
        try {
            Item item = itemMapper.selectById(id);
            if (item == null) {
                deleteItem(id);
                return;
            }
            client.index(new IndexRequest(ITEM_INDEX).id(id.toString())
                    .source(toDoc(item), XContentType.JSON), RequestOptions.DEFAULT);
            log.debug("ES文档同步完成 id={}", id);
        } catch (Exception e) {
            // ES同步失败不影响主流程，记日志便于排查
            log.error("ES文档同步失败 id={}", id, e);
        }
    }

    @Override
    public void deleteItem(Long id) {
        try {
            client.delete(new DeleteRequest(ITEM_INDEX, id.toString()), RequestOptions.DEFAULT);
            log.debug("ES文档删除完成 id={}", id);
        } catch (Exception e) {
            log.error("ES文档删除失败 id={}", id, e);
        }
    }

    @Override
    public PageDTO<ItemDTO> search(ItemPageQuery query) throws IOException {
        // 1.组装检索条件
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        // 关键字：商品名 match（IK分词，召回相关性更好的结果）
        if (StrUtil.isNotBlank(query.getKey())) {
            bool.must(QueryBuilders.matchQuery(FIELD_NAME, query.getKey()));
        }
        // 过滤：状态(只搜上架)、品牌、分类、价格区间（filter不参与打分，走缓存更快）
        bool.filter(QueryBuilders.termQuery(FIELD_STATUS, 1));
        if (StrUtil.isNotBlank(query.getBrand())) {
            bool.filter(QueryBuilders.termQuery(FIELD_BRAND, query.getBrand()));
        }
        if (StrUtil.isNotBlank(query.getCategory())) {
            bool.filter(QueryBuilders.termQuery(FIELD_CATEGORY, query.getCategory()));
        }
        if (query.getMinPrice() != null && query.getMaxPrice() != null) {
            bool.filter(QueryBuilders.rangeQuery(FIELD_PRICE).gte(query.getMinPrice()).lte(query.getMaxPrice()));
        } else if (query.getMinPrice() != null) {
            bool.filter(QueryBuilders.rangeQuery(FIELD_PRICE).gte(query.getMinPrice()));
        } else if (query.getMaxPrice() != null) {
            bool.filter(QueryBuilders.rangeQuery(FIELD_PRICE).lte(query.getMaxPrice()));
        }
        // 2.分页 + 按更新时间倒序（与原来MySQL查询行为一致）
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(bool)
                .from(query.from())
                .size(query.getPageSize())
                .sort(new FieldSortBuilder(FIELD_UPDATE_TIME).order(SortOrder.DESC));
        SearchRequest request = new SearchRequest(ITEM_INDEX).source(source);
        // 3.执行检索
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        // 4.解析结果
        SearchHits hits = response.getHits();
        long total = hits.getTotalHits().value;
        long pages = (total + query.getPageSize() - 1) / query.getPageSize();
        List<ItemDTO> list = new ArrayList<>(hits.getHits().length);
        for (SearchHit hit : hits.getHits()) {
            list.add(objectMapper.convertValue(hit.getSourceAsMap(), ItemDTO.class));
        }
        return new PageDTO<>(total, pages, list);
    }

    private void bulkIndex(List<Item> records) {
        try {
            BulkRequest bulk = new BulkRequest();
            for (Item item : records) {
                bulk.add(new IndexRequest(ITEM_INDEX).id(item.getId().toString())
                        .source(toDoc(item), XContentType.JSON));
            }
            client.bulk(bulk, RequestOptions.DEFAULT);
        } catch (Exception e) {
            throw new BizIllegalException("ES批量写入失败", e);
        }
    }

    /**
     * 商品转ES文档（字段名驼峰，时间格式 yyyy-MM-dd HH:mm:ss）。
     */
    private Map<String, Object> toDoc(Item item) {
        Map<String, Object> doc = new HashMap<>();
        doc.put(FIELD_ID, item.getId());
        doc.put(FIELD_NAME, item.getName());
        doc.put(FIELD_PRICE, item.getPrice());
        doc.put(FIELD_STOCK, item.getStock());
        doc.put(FIELD_SOLD, item.getSold());
        doc.put(FIELD_IMAGE, item.getImage());
        doc.put(FIELD_CATEGORY, item.getCategory());
        doc.put(FIELD_BRAND, item.getBrand());
        doc.put(FIELD_SPEC, item.getSpec());
        doc.put(FIELD_COMMENT_COUNT, item.getCommentCount());
        doc.put(FIELD_IS_AD, item.getIsAD());
        doc.put(FIELD_STATUS, item.getStatus());
        if (item.getCreateTime() != null) {
            doc.put(FIELD_CREATE_TIME, item.getCreateTime().format(DATE_FORMATTER));
        }
        if (item.getUpdateTime() != null) {
            doc.put(FIELD_UPDATE_TIME, item.getUpdateTime().format(DATE_FORMATTER));
        }
        return doc;
    }

    private long countDocuments() throws IOException {
        SearchSourceBuilder source = new SearchSourceBuilder().size(0);
        SearchRequest req = new SearchRequest(ITEM_INDEX).source(source);
        return client.search(req, RequestOptions.DEFAULT).getHits().getTotalHits().value;
    }
}
