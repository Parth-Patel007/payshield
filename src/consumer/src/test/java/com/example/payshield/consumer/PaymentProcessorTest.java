package com.example.payshield.consumer;

import org.apache.avro.Schema;
import org.apache.avro.generic.*;
import org.junit.jupiter.api.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class PaymentProcessorTest {

    private PaymentProcessor proc;
    private Connection mockConn;
    private PreparedStatement mockPay, mockFraud;

    @BeforeEach
    void setup() throws Exception {
        proc = new PaymentProcessor(5000.0);
        mockConn  = mock(Connection.class);
        mockPay   = mock(PreparedStatement.class);
        mockFraud = mock(PreparedStatement.class);

        when(mockConn.prepareStatement(startsWith("INSERT INTO payments")))
                .thenReturn(mockPay);
        when(mockConn.prepareStatement(startsWith("INSERT INTO fraud_alerts")))
                .thenReturn(mockFraud);
    }

    private GenericRecord makeEvent(String id, double amt, long ts) {
        Schema.Parser parser = new Schema.Parser();
        Schema schema = parser.parse("""
          {
            "type":"record","name":"Payment",
            "fields":[
              {"name":"msgId","type":"string"},
              {"name":"debitAcct","type":"string"},
              {"name":"creditAcct","type":"string"},
              {"name":"amount","type":"double"},
              {"name":"transactionTs","type":"long"}
            ]
          }
        """);
        GenericRecord r = new GenericData.Record(schema);
        r.put("msgId", id);
        r.put("debitAcct", "d");
        r.put("creditAcct","c");
        r.put("amount", amt);
        r.put("transactionTs", ts);
        return r;
    }

    @Test
    void underThresholdProducesNoFraud() throws Exception {
        GenericRecord e1 = makeEvent("t1", 100.0, 123L);
        List<GenericRecord> flagged = proc.processBatch(mockConn, List.of(e1));
        verify(mockPay, times(1)).addBatch();
        verify(mockFraud, never()).addBatch();
        Assertions.assertTrue(flagged.isEmpty());
    }

    @Test
    void overThresholdProducesFraud() throws Exception {
        GenericRecord e2 = makeEvent("t2", 9999.0, 456L);
        List<GenericRecord> flagged = proc.processBatch(mockConn, List.of(e2));
        verify(mockPay, times(1)).addBatch();
        verify(mockFraud, times(1)).addBatch();
        Assertions.assertEquals(1, flagged.size());
        Assertions.assertEquals("t2", flagged.get(0).get("msgId").toString());
    }
}