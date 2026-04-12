
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
    //private static final long CACHE_EXPIRY = 3300000;

    private static String apiKey;
    private static String serviceCRN;
    private static String apiVersion;
    private static HttpClient client;
    
    private static Path cacheFile = Paths.get("token.cache");
    private static Path expiryFile = Paths.get("token_expiry.cache");
    //private static long tokenExpiryTime = 0;

    private static Integer BUFFER_SECONDS = 300;
    private static Integer MS_PER_SECOND = 1000;

    public static void main(String[] args) {
        
        Properties prop = new Properties();
        String token = null;

        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        
        try (FileInputStream envFile = new FileInputStream(".env")) {
            prop.load(envFile);
            apiKey = prop.getProperty("IBM_CLOUD_API_KEY");
            serviceCRN = prop.getProperty("IBM_SERVICE_CRN");
            apiVersion = prop.getProperty("IBM_API_VERSION", "2026-02-15");
        } catch (IOException e) {
            System.err.println("Configuration Error: " + e.getMessage());
            return;
        }

        try {
            if (Files.exists(cacheFile) && Files.exists(expiryFile)) {
                long expiryDeadline = Long.parseLong(Files.readString(expiryFile));
                if (System.currentTimeMillis() < expiryDeadline) {
                    token = Files.readString(cacheFile);
                    System.out.println("Using cached token. Valid until: " + new java.util.Date(expiryDeadline));
                }
            }
        } catch (Exception e) { System.out.println("Cache miss: " + e.getMessage()); }

        if (token == null) {
            token = fetchAndCacheToken();
        } else {
            processArchive(token); 
        }

        updateLastRun();
    }

    private static String fetchAndCacheToken() {
        HttpRequest authReq = HttpRequest.newBuilder()
            .uri(URI.create(IBM_AUTH_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=urn:ibm:params:oauth:grant-type:apikey&apikey=" + apiKey))
            .build();

        return client.sendAsync(authReq, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                try {
                    JsonNode node = new ObjectMapper().readTree(resp.body());
                    String token = node.get("access_token").asText();
                    
                    // 1. Extract dynamic expiry (IBM usually sends 3600) 
                    long secondsValid = node.get("expires_in").asLong();
                    
                    // 2. Calculate absolute deadline (Current Time + Validity - 5 min buffer)
                    long deadline = System.currentTimeMillis() + ((secondsValid - BUFFER_SECONDS) * MS_PER_SECOND);
                    
                    // 3. Save the vault partners
                    Files.writeString(cacheFile, token);
                    Files.writeString(expiryFile, String.valueOf(deadline));
                    
                    processArchive(token); 
                    return token;
                } catch (Exception e) { 
                    System.err.println("Auth Error: " + e.getMessage());
                    return null; 
                }
            }).join();
    }

    private static void processArchive(String token) {
        Path dailyDir = Paths.get("quantum_data", LocalDate.now().toString());
        
        HttpRequest listReq = HttpRequest.newBuilder()
            .uri(URI.create("https://quantum.cloud.ibm.com/api/v1/backends"))
            .header("Authorization", "Bearer " + token)
            .header("Service-CRN", serviceCRN)
            .header("IBM-API-Version", apiVersion)
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
                            
                            String name = node.isObject() ? node.path("name").asText() : node.asText();
                            // String name = node.get("name").asText();
                            
                            // Extract the median error from the index data
                            if (node.has("performance_metrics")) {
                                double medianError = node.path("performance_metrics")
                                                        .path("two_q_error_median")
                                                        .path("value")
                                                        .asDouble();
                                System.out.println("Backend: " + name + " | 2Q Error Median: " + medianError);
                            }

                            if (name != null && !name.isEmpty()) {
                                tasks.add(fetchDetails(token, name, dailyDir));
                            }
                        }
                    }

                    CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
                    System.out.println("Snapshot complete: " + dailyDir);
                } catch (Exception e) { e.printStackTrace(); }
            }).join();
    }

    private static CompletableFuture<Void> fetchDetails(String token, String name, Path dailyDir) {
        
        String url = "https://quantum.cloud.ibm.com/api/v1/backends/" + name + "/properties";
        
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Service-CRN", serviceCRN)
            .header("IBM-API-Version", apiVersion)
            .GET().build();

        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> {
                try {
                    Path backendFile = dailyDir.resolve(name + ".json");
                    Files.writeString(backendFile, resp.body());
                } catch (IOException e) { System.err.println("Error saving " + name); }
            });
    }

    private static void updateLastRun() {
        try {
            Properties prop = new Properties();
            
            try (FileInputStream in = new FileInputStream(".env")) { prop.load(in); }
            prop.setProperty("LAST_RUN", java.time.LocalDateTime.now().toString());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(".env")) {
                prop.store(out, "Updated by ArchiveEngine");
            }
        } catch (IOException e) { System.err.println("Could not update LAST_RUN"); }
    }
}