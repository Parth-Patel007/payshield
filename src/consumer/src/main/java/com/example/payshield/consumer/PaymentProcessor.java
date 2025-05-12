package com.example.payshield.consumer;

import org.apache.avro.generic.GenericRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic: for a batch of events, build SQL batches
 * and collect which events should be fanned out as fraud.
 */
public class PaymentProcessor {

    private final double fraudThreshold;

    public PaymentProcessor(double fraudThreshold) {
        this.fraudThreshold = fraudThreshold;
    }

    /**
     * Given a JDBC connection and a list of events, execute
     * the payments + fraud_alerts inserts, and return the
     * list of fraud GenericRecords for downstream fan-out.
     */
    public List<GenericRecord> processBatch(
            Connection db,
            List<GenericRecord> events
    ) throws SQLException {
        String paySql   = "INSERT INTO payments VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING";
        String fraudSql = "INSERT INTO fraud_alerts VALUES (?,?,?,?) ON CONFLICT DO NOTHING";

        try (PreparedStatement payStmt  = db.prepareStatement(paySql);
             PreparedStatement fraudStmt = db.prepareStatement(fraudSql)) {

            List<GenericRecord> flagged = new ArrayList<>();
            for (GenericRecord evt : events) {
                String msgId      = evt.get("msgId").toString();
                String debitAcct  = evt.get("debitAcct").toString();
                String creditAcct = evt.get("creditAcct").toString();
                double amount     = (double) evt.get("amount");
                Timestamp ts      = new Timestamp((long) evt.get("transactionTs"));

                // payments
                payStmt.setString(1, msgId);
                payStmt.setString(2, debitAcct);
                payStmt.setString(3, creditAcct);
                payStmt.setDouble(4, amount);
                payStmt.setTimestamp(5, ts);
                payStmt.addBatch();

                // fraud?
                if (amount >= fraudThreshold) {
                    fraudStmt.setString(1, msgId);
                    fraudStmt.setDouble(2, amount);
                    fraudStmt.setTimestamp(3, ts);
                    fraudStmt.setString(4, "THRESHOLD_EXCEEDED");
                    fraudStmt.addBatch();

                    flagged.add(evt);
                }
            }

            payStmt.executeBatch();
            fraudStmt.executeBatch();
            return flagged;
        }
    }
}
