
/**
 * IBM Quantum Entropy Archive - Cleaning Engine
 * Copyright (c) 2026 Calder Henry. All rights reserved.
 */

package com.calder.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class ArchiveEngine {
    private static final String IBM_AUTH_URL = "https://iam.cloud.ibm.com/identity/token";
    private static final long CACHE_EXPIRY = 3300000;

    public static void main(String[] args) {
        Properties prop = new Properties();
        try (FileInputStream envFile = new FileInputStream(".env")) {
            prop.load(envFile);
        } catch (IOException e) {
            System.err.println("Check .env file.");
            return;
        }

        String apiKey = prop.getProperty("IBM_CLOUD_API_KEY");
        String serviceCRN = prop.getProperty("IBM_SERVICE_CRN");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

        Path cacheFile = Paths.get("token.cache");
        String token = null;

        // 1. Check Token Cache
        try {
            if (Files.exists(cacheFile) && (System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile).toMillis()) < CACHE_EXPIRY) {
                token = Files.readString(cacheFile);
                System.out.println("Using cached token.");
            }
        } catch (Exception e) {System.out.println("Access Token Expired" + e.getMessage());}

        if (token == null) {
            token = fetchAndCacheToken(client, apiKey, cacheFile, serviceCRN);
        } else {
            processArchive(client, token, serviceCRN); 
        }
    }

    private static String fetchAndCacheToken(HttpClient client, String apiKey, Path cacheFile, String serviceCRN) {
        HttpRequest authReq = HttpRequest.newBuilder()
            .uri(URI.create(IBM_AUTH_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=urn:ibm:params:oauth:grant-type:apikey&apikey=" + apiKey))
            .build();

        String token = client.sendAsync(authReq, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                try { return new ObjectMapper().readTree(resp.body()).get("access_token").asText(); } 
                catch (Exception e) { return null; }
            }).join();

        if (token != null) {
            try { Files.writeString(cacheFile, token); } 
            catch (IOException e) { System.err.println("Failed to write cache"); }
            // Note: main also calls processArchive, so you may want to remove this line if it runs twice
            processArchive(client, token, serviceCRN); 
        }
        return token;
    }

    private static void processArchive(HttpClient client, String token, String serviceCRN) {
        Path dailyDir = Paths.get("quantum_data", LocalDate.now().toString());
        
        HttpRequest listReq = HttpRequest.newBuilder()
            .uri(URI.create("https://quantum.cloud.ibm.com/api/v1/backends"))
            .header("Authorization", "Bearer " + token)
            .header("Service-CRN", serviceCRN)
            .header("IBM-API-Version", "2026-02-15")
            .GET().build();

        client.sendAsync(listReq, HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> {
                try {

                    if (resp.statusCode() != 200) {
                        System.err.println("API Error " + resp.statusCode() + ": " + resp.body());
                        return;
                    }
                    
                    Files.createDirectories(dailyDir);
                    Files.writeString(dailyDir.resolve("backends_index.json"), resp.body());

                    // 1. Get the "devices" node
                    JsonNode devices = new ObjectMapper().readTree(resp.body()).get("devices");
                    List<CompletableFuture<Void>> tasks = new ArrayList<>();

                    if (devices != null && devices.isArray()) {
                        for (JsonNode node : devices) {
                            if (node.has("name")) {
                                String name = node.get("name").asText();
                                
                                // Extract the median error from the index data
                                if (node.has("performance_metrics")) {
                                    double medianError = node.path("performance_metrics")
                                                            .path("two_q_error_median")
                                                            .path("value")
                                                            .asDouble();
                                    System.out.println("Backend: " + name + " | 2Q Error Median: " + medianError);
                                }

                                // Still trigger the full properties download
                                tasks.add(fetchDetails(client, token, serviceCRN, name, dailyDir));
                            }
                        }
                    }

                    CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
                    System.out.println("Snapshot complete: " + dailyDir);
                } catch (Exception e) { e.printStackTrace(); }
            }).join();
    }

    private static CompletableFuture<Void> fetchDetails(HttpClient client, String token, String serviceCRN, String name, Path dailyDir) {
        String url = "https://quantum.cloud.ibm.com/api/v1/backends/" + name + "/properties";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Service-CRN", serviceCRN)
            .header("IBM-API-Version", "2026-02-15")
            .GET().build();

        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> {
                try {
                    Path backendFile = dailyDir.resolve(name + ".json");
                    Files.writeString(backendFile, resp.body());
                } catch (IOException e) { System.err.println("Error saving " + name); }
            });
    }
}