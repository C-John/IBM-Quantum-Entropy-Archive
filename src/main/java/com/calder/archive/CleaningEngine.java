
/**
 * IBM Quantum Entropy Archive - Cleaning Engine
 * Copyright (c) 2026 Calder Henry. All rights reserved.
 */

package com.calder.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class CleaningEngine {

    // Using "static" so the main method can see it without an instance
    public static class QubitMetric {
        String timestamp;
        String machineName;
        int qubitId;
        double t1; 
        double t2; 
        double readoutError;
        double gateError;
    }

    public static void main(String[] args) {
        // Points to the folder created by ArchiveEngine today
        Path dailyDir = Paths.get("quantum_data", LocalDate.now().toString());
        
        CleaningEngine engine = new CleaningEngine();
        
        try {
            engine.processDailyFolder(dailyDir);
        } catch (Exception e) {
            System.err.println("Error processing folder: " + e.getMessage());
        }
    }

    public void processDailyFolder(Path dateFolder) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<QubitMetric> dailyMetrics = new ArrayList<>();

        // OUTER LOOP: Iterate through every file in the date folder
        Files.list(dateFolder).forEach(filePath -> {
            String fileName = filePath.getFileName().toString();

            // Skip the index file; we only want the individual machine data
            if (fileName.endsWith(".json") && !fileName.equals("backends_index.json")) {
                try {
                    System.out.println("Cleaning: " + fileName);
                    
                    // Parse the file into a "Node" tree
                    JsonNode machineRoot = mapper.readTree(filePath.toFile());
                    String machineName = machineRoot.get("backend_name").asText();

                    // INNER LOOP: Step into the "qubits" array
                    JsonNode qubitsArray = machineRoot.get("qubits");
                    if (qubitsArray != null && qubitsArray.isArray()) {
                        for (JsonNode qubitNode : qubitsArray) {
                            
                            // Map JSON to your Blueprint
                            QubitMetric metric = new QubitMetric();
                            metric.machineName = machineName;
                            metric.qubitId = qubitNode.get("qubit").asInt();
                            
                            // We will add logic here to find T1/T2 inside the nested properties
                            dailyMetrics.add(metric);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Failed to parse " + fileName);
                }
            }
        });

        System.out.println("Total Qubit rows prepared: " + dailyMetrics.size());
        // Step 3: Pass dailyMetrics to the Parquet Writer logic next
    }
}