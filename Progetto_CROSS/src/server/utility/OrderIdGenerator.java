package server.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

//Gestisce la generazione di ID univoci per gli ordini del sistema CROSS
public class OrderIdGenerator {

    // Generatore thread-safe per orderId univoci
    private static final AtomicInteger orderIdCounter = new AtomicInteger(1);

    // Flag per evitare inizializzazioni multiple
    private static volatile boolean initialized = false;

    // File storico da cui leggere l'ultimo ID utilizzato
    private static final String STORICO_FILE = "data/StoricoOrdini.json";

    //Inizializza il generatore di ID leggendo l'ultimo ID dal file
    public static synchronized void initialize() throws IOException {
        if (initialized) {
            return;
        }

        try {
            File storicoFile = new File(STORICO_FILE);

            if (!storicoFile.exists()) {
                orderIdCounter.set(1);
                System.out.println("[OrderIdGenerator] File storico non trovato - inizializzo da ID 1");
            } else {
                int maxOrderId = findMaxOrderIdFromFile();
                orderIdCounter.set(maxOrderId + 1);
            }

            initialized = true;

        } catch (Exception e) {
            System.err.println("[OrderIdGenerator] Errore inizializzazione: " + e.getMessage());
            orderIdCounter.set(10000);
            initialized = true;
            throw new IOException("Errore inizializzazione OrderIdGenerator", e);
        }
    }

    //Genera il prossimo ID univoco per un nuovo ordine
    public static int getNextOrderId() {
        if (!initialized) {
            throw new IllegalStateException("OrderIdGenerator non inizializzato - chiamare initialize() prima");
        }

        int nextId = orderIdCounter.getAndIncrement();

        return nextId;
    }

    //Ottiene l'ultimo ID generato (senza incrementare)
    public static int getCurrentId() {
        return orderIdCounter.get() - 1; // Sottrae 1 perché counter punta al prossimo
    }

    //Ottiene il prossimo ID che verrà generato (senza incrementare)
    public static int getNextIdPreview() {
        return orderIdCounter.get();
    }

    //Verifica se il generatore è stato inizializzato
    public static boolean isInitialized() {
        return initialized;
    }

    //Ottiene statistiche del generatore per monitoring
    public static JsonObject getStats() {
        JsonObject stats = new JsonObject();
        stats.addProperty("initialized", initialized);
        stats.addProperty("currentId", getCurrentId());
        stats.addProperty("nextId", getNextIdPreview());
        stats.addProperty("totalGenerated", getCurrentId()); // Assumendo start da 1
        return stats;
    }

    //Trova l'orderID massimo presente nel file storico
    private static int findMaxOrderIdFromFile() throws IOException {
        try (FileReader reader = new FileReader(STORICO_FILE)) {
            JsonObject rootObject = JsonParser.parseReader(reader).getAsJsonObject();

            if (!rootObject.has("trades")) {
                return 0;
            }

            JsonArray trades = rootObject.getAsJsonArray("trades");
            int maxOrderId = 0;

            // Scansiona tutti i record per trovare l'ID massimo
            for (int i = 0; i < trades.size(); i++) {
                JsonObject trade = trades.get(i).getAsJsonObject();

                if (trade.has("orderId")) {
                    try {
                        int orderId = trade.get("orderId").getAsInt();
                        if (orderId > maxOrderId) {
                            maxOrderId = orderId;
                        }
                    } catch (NumberFormatException e) {
                        // Ignora record con orderId non valido
                        System.out.println("[OrderIdGenerator] Ignorato orderId non valido nel record " + i);
                    }
                }
            }
            return maxOrderId;

        } catch (Exception e) {
            System.err.println("[OrderIdGenerator] Errore lettura file storico: " + e.getMessage());
            throw new IOException("Impossibile leggere file storico", e);
        }
    }
}
