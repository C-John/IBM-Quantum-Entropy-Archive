
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
import org.apache.avro.Schema;
import java.io.InputStream;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.hadoop.util.HadoopOutputFile;
import org.apache.avro.generic.GenericData;

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

        Path baseDir = Paths.get("quantum_data");
        CleaningEngine engine = new CleaningEngine();
        
        try {
            Files.list(baseDir)
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    try {
                        Path parquetFile = dir.resolve("daily_snapshot.parquet");
                        if (!Files.exists(parquetFile)) {
                            engine.processDailyFolder(dir);
                        } else {
                            System.out.println("Skipping " + dir.getFileName() + " - already processed.");
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing " + dir + ": " + e.getMessage());
                    }
                });
        } catch (IOException e) {
            System.err.println("Could not list quantum_data: " + e.getMessage());
        }

        System.exit(0);
    }


        // Points to the folder created by ArchiveEngine today
        // Path dailyDir = Paths.get("quantum_data", LocalDate.now().toString());
        
    //     CleaningEngine engine = new CleaningEngine();
        
    //     try {
    //         engine.processDailyFolder(dailyDir);
    //     } catch (Exception e) {
    //         System.err.println("Error processing folder: " + e.getMessage());
    //     }

    //     System.exit(0);
    // }

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
                        
                        // Use a standard for-loop so the index (i) becomes our Qubit ID
                        for (int i = 0; i < qubitsArray.size(); i++) {
                            
                            // In IBM's schema, the node itself IS the array of properties
                            JsonNode qubitProperties = qubitsArray.get(i); 

                            QubitMetric metric = new QubitMetric();
                            metric.timestamp = machineRoot.path("last_update_date").asText("Unknown");
                            metric.machineName = machineName;
                            metric.qubitId = i;

                            // Iterate directly over the properties array
                            if (qubitProperties != null && qubitProperties.isArray()) {
                                for (JsonNode prop : qubitProperties) {
                                    String name = prop.path("name").asText("");
                                    double value = prop.path("value").asDouble(0.0);

                                    // Map specific IBM metrics
                                    if (name.equals("T1")) metric.t1 = value;
                                    else if (name.equals("T2")) metric.t2 = value;
                                    else if (name.equals("readout_error")) metric.readoutError = value;
                                }
                            }
                            dailyMetrics.add(metric);
                        }
                    } 
                } catch (IOException e) {
                    System.err.println("Failed to parse " + fileName);
                }
            }
        });

        System.out.println("Total Qubit rows prepared: " + dailyMetrics.size());
        
        // Pass dailyMetrics to the Parquet Writer logic next
        InputStream schemaStream = getClass().getResourceAsStream("/qubit_metric.avsc");
        Schema schema = new Schema.Parser().parse(schemaStream);

        org.apache.hadoop.fs.Path outputPath = new org.apache.hadoop.fs.Path(dateFolder.resolve("daily_snapshot.parquet").toString());
        Configuration conf = new Configuration();

        ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(HadoopOutputFile.fromPath(outputPath, conf))
            .withSchema(schema)
            .withConf(conf)
            .withCompressionCodec(CompressionCodecName.SNAPPY)
            .build();

        // Iterate and write each qubit metric to the Parquet file
        for (QubitMetric m : dailyMetrics) {
            GenericRecord record = new GenericData.Record(schema);
            
            // Mapping Java fields to Avro schema columns
            record.put("timestamp", m.timestamp != null ? m.timestamp : java.time.LocalDateTime.now().toString());
            record.put("machineName", m.machineName);
            record.put("qubitId", m.qubitId);
            record.put("t1", m.t1);
            record.put("t2", m.t2);
            record.put("readoutError", m.readoutError);
            record.put("gateError", m.gateError);
            
            writer.write(record);
        }

        // Strictly required to flush data to disk and release the file lock
        writer.close();
        System.out.println("Successfully archived " + dailyMetrics.size() + " rows to Parquet.");
    }
}