package com.unir.products.service;

import java.util.List;

import com.unir.products.model.db.Product;
import com.unir.products.model.request.CreateProductRequest;
import com.unir.products.model.response.ProductsQueryResponse;

public interface ProductsService {

	ProductsQueryResponse getProducts(String name, String description, String country, Boolean aggregate);
	
	Product getProduct(String productId);
	
	Boolean removeProduct(String productId);
	
	Product createProduct(CreateProductRequest request);

	public List<String> getSuggestions(String name) ;

	public void updateProduct(Product product);

	public List<String> getCountryFacets();

	public Long getVisibleProductCount();
}
