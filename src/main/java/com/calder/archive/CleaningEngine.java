
/**
 * IBM Quantum Entropy Archive - Cleaning Engine
 * Copyright (c) 2026 Calder Henry. All rights reserved.
 */

package com.calder.archive;

import java.nio.file.Path;
import java.util.List;

public class CleaningEngine {

    // This is the "Blueprint" for one row of your Parquet file
    public static class QubitMetric {
        String timestamp;
        String machineName;
        int qubitId;
        double t1; // Relaxation Time (µs)
        double t2; // Dephasing Time (µs)
        double readoutError;
        double gateError;
    }

    public void processDailyFolder(Path dateFolder) {
        // Step 2 logic will go here: 
        // 1. Loop through files like ibm_kingston.json
        // 2. Flatten the data into QubitMetric objects
    }
}