package com.llamaaistudio.app.data.hf

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface HfApi {
    @GET("api/models")
    suspend fun searchModels(
        @Query("search") search: String,
        @Query("filter") filter: String = "gguf",
        @Query("limit") limit: Int = 30,
    ): List<HfModelSummary>

    @GET("api/models/{id}")
    suspend fun getModelDetail(
        @Path("id", encoded = true) id: String,
        @Query("blobs") blobs: Boolean = true,
    ): HfModelDetail

    companion object {
        const val BASE_URL = "https://huggingface.co/"

        fun create(okHttpClient: okhttp3.OkHttpClient): HfApi {
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            return retrofit2.Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(moshi))
                .build()
                .create(HfApi::class.java)
        }
    }
}
