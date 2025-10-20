package server.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Genera ID univoci per gli ordini
 */
public class OrderIdGenerator {

    // Generatore orderId
    private static final AtomicInteger orderIdCounter = new AtomicInteger(1);

    // Flag per evitare inizializzazioni multiple
    private static volatile boolean initialized = false;

    // File storico da cui leggere l'ultimo ID utilizzato
    private static final String STORICO_FILE = "data/StoricoOrdini.json";


    public static synchronized void initialize() throws IOException {
        if (initialized) {
            return;
        }

        try {
            File storicoFile = new File(STORICO_FILE);

            if (!storicoFile.exists()) {
                orderIdCounter.set(1);
            } else {
                int maxOrderId = findMaxOrderIdFromFile();
                orderIdCounter.set(maxOrderId + 1);
            }

            initialized = true;

        } catch (Exception e) {
            orderIdCounter.set(10000);
            initialized = true;
            throw new IOException("[OrderIdGenerator] Errore inizializzazione OrderIdGenerator", e);
        }
    }

    public static int getNextOrderId() {
        if (!initialized) {
            throw new IllegalStateException("OrderIdGenerator non inizializzato");
        }
        return orderIdCounter.getAndIncrement();
    }

    private static int findMaxOrderIdFromFile() throws IOException {
        try (FileReader reader = new FileReader(STORICO_FILE)) {
            JsonObject rootObject = JsonParser.parseReader(reader).getAsJsonObject();

            if (!rootObject.has("trades")) {
                return 0;
            }

            JsonArray trades = rootObject.getAsJsonArray("trades");
            int maxOrderId = 0;

            for (int i = 0; i < trades.size(); i++) {
                JsonObject trade = trades.get(i).getAsJsonObject();

                if (trade.has("orderId")) {
                    try {
                        int orderId = trade.get("orderId").getAsInt();
                        if (orderId > maxOrderId) {
                            maxOrderId = orderId;
                        }
                    } catch (NumberFormatException e) {
                        continue;
                    }
                }
            }
            return maxOrderId;

        } catch (Exception e) {
            throw new IOException("[OrderIdGenerator] Impossibile leggere file storico", e);
        }
    }
}