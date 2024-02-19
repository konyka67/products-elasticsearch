package com.unir.products.data;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;

import com.unir.products.model.db.Product;
import com.unir.products.model.response.AggregationDetails;
import com.unir.products.model.response.ProductsQueryResponse;
import lombok.SneakyThrows;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder.Type;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedStringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.SuggestBuilders;
import org.elasticsearch.search.suggest.completion.CompletionSuggestion;
import org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchPage;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class DataAccessRepository {
    @Value("${server.fullAddress}")
    private String serverFullAddress;

    // Esta clase (y bean) es la unica que usan directamente los servicios para
    // acceder a los datos.
    private final ProductRepository productRepository;
    private final ElasticsearchOperations elasticClient;
  

    private final String[] descriptionSearchFields = {"description", "description._2gram", "description._3gram"};

    public Product save(Product product) {
        return productRepository.save(product);
    }

    public Boolean delete(Product product) {
        productRepository.delete(product);
        return Boolean.TRUE;
    }

	public Optional<Product> findById(String id) {
		return productRepository.findById(id);
	}
 

    @SneakyThrows
    public ProductsQueryResponse findProducts(String name, String description, String country, Boolean aggregate) {

        BoolQueryBuilder querySpec = QueryBuilders.boolQuery();

        if (!StringUtils.isEmpty(country)) {
            querySpec.must(QueryBuilders.termQuery("country", country));
        }

        if (!StringUtils.isEmpty(name)) {
            querySpec.must(QueryBuilders.matchQuery("name", name));
        }

        if (!StringUtils.isEmpty(description)) {
            querySpec.must(QueryBuilders.multiMatchQuery(description, descriptionSearchFields).type(Type.BOOL_PREFIX));
        }

        //Si no he recibido ningun parametro, busco todos los elementos.
        if (!querySpec.hasClauses()) {
            querySpec.must(QueryBuilders.matchAllQuery());
        }

        //Filtro implicito
        //No le pido al usuario que lo introduzca pero lo aplicamos proactivamente en todas las peticiones
        //En este caso, que los productos sean visibles (estado correcto de la entidad)
        querySpec.must(QueryBuilders.termQuery("visible", true));

        NativeSearchQueryBuilder nativeSearchQueryBuilder = new NativeSearchQueryBuilder().withQuery(querySpec);

        if (aggregate) {
            nativeSearchQueryBuilder.addAggregation(AggregationBuilders.terms("Country Aggregation").field("country").size(1000));
            nativeSearchQueryBuilder.withMaxResults(0);
        }

        //Opcionalmente, podemos paginar los resultados
        //nativeSearchQueryBuilder.withPageable(PageRequest.of(0, 10));

        Query query = nativeSearchQueryBuilder.build();
        SearchHits<Product> result = elasticClient.search(query, Product.class);

        List<AggregationDetails> responseAggs = new LinkedList<>();

        if (result.hasAggregations()) {
            Map<String, Aggregation> aggs = result.getAggregations().asMap();
            ParsedStringTerms countryAgg = (ParsedStringTerms) aggs.get("Country Aggregation");

            //Componemos una URI basada en serverFullAddress y query params para cada argumento, siempre que no viniesen vacios
            String queryParams = getQueryParams(name, description, country);
            countryAgg.getBuckets()
                    .forEach(
                            bucket -> responseAggs.add(
                                    new AggregationDetails(
                                            bucket.getKey().toString(),
                                            (int) bucket.getDocCount(),
                                            serverFullAddress + "/products?country=" + bucket.getKey() + queryParams)));
        }
        return new ProductsQueryResponse(result.getSearchHits().stream().map(SearchHit::getContent).toList(), responseAggs);
    }

    /**
     * Componemos una URI basada en serverFullAddress y query params para cada argumento, siempre que no viniesen vacios
     *
     * @param name        - nombre del producto
     * @param description - descripcion del producto
     * @param country     - pais del producto
     * @return
     */
    private String getQueryParams(String name, String description, String country) {
        String queryParams = (StringUtils.isEmpty(name) ? "" : "&name=" + name)
                + (StringUtils.isEmpty(description) ? "" : "&description=" + description);
        // Eliminamos el ultimo & si existe
        return queryParams.endsWith("&") ? queryParams.substring(0, queryParams.length() - 1) : queryParams;
    }

 
    public List<String> getSuggestions(String name) {
        List<String> suggestions = new ArrayList<>();
        
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        queryBuilder.withQuery(QueryBuilders.matchQuery("name", name)); 

        SearchHits<Product> searchHits = elasticClient.search(queryBuilder.build(), Product.class);
        
        for (SearchHit<Product> searchHit : searchHits) {
            Product product = searchHit.getContent();
            String suggestion = product.getName();
            suggestions.add(suggestion);
        }
        
        return suggestions;
    }

    public void updateProduct(Product product) {
        IndexQueryBuilder indexQueryBuilder = new IndexQueryBuilder();
        indexQueryBuilder.withId(product.getId());
        indexQueryBuilder.withObject(product);

        String indexName = "products";//indice en elastiksearch

        elasticClient.index(indexQueryBuilder.build(), IndexCoordinates.of(indexName));
  
    }

    public List<String> getCategoryFacets() {
        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .addAggregation(AggregationBuilders.terms("categoryFacets").field("category").size(10))
                .build();

        SearchHits<Product> searchHits = elasticClient.search(searchQuery, Product.class);

        List<String> categoryFacets = new ArrayList<>();
        Terms categoryTerms = searchHits.getAggregations().get("categoryFacets");
        for (Terms.Bucket bucket : categoryTerms.getBuckets()) {
            categoryFacets.add(bucket.getKeyAsString());
        }
        return categoryFacets;
    }



    public List<String> getCountryFacets() {
        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .addAggregation(AggregationBuilders.terms("countryFacets").field("country").size(10))
                .build();

        SearchHits<Product> searchHits = elasticClient.search(searchQuery, Product.class);

        List<String> countryFacets = new ArrayList<>();
        Terms countryTerms = searchHits.getAggregations().get("countryFacets");
        for (Terms.Bucket bucket : countryTerms.getBuckets()) {
            countryFacets.add(bucket.getKeyAsString());
        }
        return countryFacets;
    }

    public Long getVisibleProductCount() {
        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .withQuery(QueryBuilders.termQuery("visible", true))
                .build();

        SearchHits<Product> searchHits = elasticClient.search(searchQuery, Product.class);
        return searchHits.getTotalHits();
    }
 

}