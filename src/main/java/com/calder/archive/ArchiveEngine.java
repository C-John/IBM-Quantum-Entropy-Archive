package com.calder.archive;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * IBM Quantum Entropy Archive - Cleaning Engine
 * Copyright (c) 2026 Calder Henry. All rights reserved.
 */
public class ArchiveEngine {

    private static final String IBM_AUTH_URL = "https://iam.cloud.ibm.com/identity/token";

    private static void pullQuantumData(HttpClient client, String token, String serviceCRN) {
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://quantum.cloud.ibm.com/api/v1/backends"))
            .header("Authorization", "Bearer " + token)
            .header("Service-CRN", serviceCRN)
            .header("IBM-API-Version", "2026-02-15")
            .header("accept", "application/json")
            .GET()
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(HttpResponse::body)
            .thenAccept(body -> {
                String chunk = body.length() > 1000 ? body.substring(0, 1000) : body;
                System.out.println("Data Sample:\n" + chunk + "...");
            })
            .join();
    }
    public static void main(String[] args) {

        Configuration conf = new Configuration();
        
        Properties prop = new Properties();
        String apiKey = null;
        String serviceCRN = null;

        System.out.println("============================================");
        System.out.println("Initializing IBM Quantum Entropy Archive...");
        System.out.println("============================================");
        
        // Testing if the Hadoop dependency from pom.xml is active
        if (conf != null){
            System.out.println("Environment Status: Hadoop Configuration Loaded.");            
            System.out.println("\nReady to process high-entropy data.");
        }
        
        try (FileInputStream envFile = new FileInputStream(".env")) {
            prop.load(envFile);
            
            apiKey = prop.getProperty("IBM_CLOUD_API_KEY");
            
            if (apiKey != null) {
                System.out.println("Success: API Key loaded from .env without external libraries.");
            }

            serviceCRN = prop.getProperty("IBM_SERVICE_CRN");
            
            if (serviceCRN != null) {
                System.out.println("Success: Service-CRN loaded from .env without external libraries.");
            }

        } catch (IOException e) {
            System.err.println("Error: Could not find or read the .env file.");
        }

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
            
        // HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(IBM_AUTH_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(
                "grant_type=urn:ibm:params:oauth:grant-type:apikey&apikey=" + apiKey
            ))
            .build();

        Path cacheFile = Paths.get("token.cache");

        try {
            if (Files.exists(cacheFile) && (System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile).toMillis()) < 3300000) {
                System.out.println("Loaded token from cache.");
                pullQuantumData(client, Files.readString(cacheFile), serviceCRN);
                return; 
            }
        } catch (Exception e) {}

        final String currentCRN = serviceCRN;
        
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                try {
                    return new ObjectMapper().readTree(response.body()).get("access_token").asText();
                } catch (Exception e) { return null; }
            })
            .thenAccept(newToken -> {
                if (newToken != null) {
                    try {
                        Files.writeString(cacheFile, newToken); 
                        System.out.println("New token acquired and cached.");
                        pullQuantumData(client, newToken, currentCRN);
                    } catch (Exception e) {}
                }
            }).join();

        // client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        //     .thenApply(HttpResponse::body)
        //     .thenAccept(System.out::println)
        //     .join();
    }
}