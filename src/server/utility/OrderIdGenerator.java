package server.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestisce la generazione di ID univoci per gli ordini del sistema CROSS
 *
 * Responsabilità:
 * - Generazione thread-safe di orderID sequenziali
 * - Inizializzazione dal file storico esistente
 * - Garanzia di unicità anche dopo restart del server
 * - Performance ottimizzata con AtomicInteger
 *
 * Estratto da OrderManager per principio Single Responsibility
 * Thread-safe per uso in ambiente multithreaded del server
 */
public class OrderIdGenerator {

    // Generatore thread-safe per orderId univoci
    private static final AtomicInteger orderIdCounter = new AtomicInteger(1);

    // Flag per evitare inizializzazioni multiple
    private static volatile boolean initialized = false;

    // File storico da cui leggere l'ultimo ID utilizzato
    private static final String STORICO_FILE = "data/StoricoOrdini.json";

    /**
     * Inizializza il generatore di ID leggendo l'ultimo ID dal file storico
     * Garantisce che gli ID siano sempre univoci anche dopo restart del server
     *
     * Thread-safe: può essere chiamato da più thread senza problemi
     *
     * @throws IOException se errori nella lettura del file storico
     */
    public static synchronized void initialize() throws IOException {
        if (initialized) {
            return; // Evita inizializzazioni multiple
        }

        try {
            File storicoFile = new File(STORICO_FILE);

            if (!storicoFile.exists()) {
                // File non esiste: inizia da 1
                orderIdCounter.set(1);
                System.out.println("[OrderIdGenerator] File storico non trovato - inizializzo da ID 1");
            } else {
                // File esistente: trova l'ID massimo e parte da quello + 1
                int maxOrderId = findMaxOrderIdFromFile();
                orderIdCounter.set(maxOrderId + 1);
                System.out.println("[OrderIdGenerator] Inizializzato da storico - prossimo ID: " + orderIdCounter.get());
            }

            initialized = true;

        } catch (Exception e) {
            System.err.println("[OrderIdGenerator] Errore inizializzazione: " + e.getMessage());
            // In caso di errore, inizializza con valore safe
            orderIdCounter.set(10000); // Valore alto per evitare conflitti
            initialized = true;
            throw new IOException("Errore inizializzazione OrderIdGenerator", e);
        }
    }

    /**
     * Genera il prossimo ID univoco per un nuovo ordine
     * Thread-safe: può essere chiamato da più thread contemporaneamente
     *
     * @return prossimo ID univoco disponibile
     * @throws IllegalStateException se chiamato prima dell'inizializzazione
     */
    public static int getNextOrderId() {
        if (!initialized) {
            throw new IllegalStateException("OrderIdGenerator non inizializzato - chiamare initialize() prima");
        }

        int nextId = orderIdCounter.getAndIncrement();

        // Log periodico per debugging (ogni 100 ordini)
        if (nextId % 100 == 0) {
            System.out.println("[OrderIdGenerator] Generato orderID: " + nextId);
        }

        return nextId;
    }

    /**
     * Ottiene l'ultimo ID generato (senza incrementare)
     * Utile per debugging e statistiche
     *
     * @return ultimo ID generato
     */
    public static int getCurrentId() {
        return orderIdCounter.get() - 1; // Sottrae 1 perché counter punta al prossimo
    }

    /**
     * Ottiene il prossimo ID che verrà generato (senza incrementare)
     * Utile per preview e validazioni
     *
     * @return prossimo ID che sarà generato
     */
    public static int getNextIdPreview() {
        return orderIdCounter.get();
    }

    /**
     * Reset del generatore (SOLO per testing!)
     * NON utilizzare in produzione
     *
     * @param startValue nuovo valore di partenza
     */
    public static synchronized void resetForTesting(int startValue) {
        if (startValue < 1) {
            throw new IllegalArgumentException("Start value deve essere >= 1");
        }

        orderIdCounter.set(startValue);
        initialized = true;

        System.out.println("[OrderIdGenerator] RESET PER TESTING - nuovo start: " + startValue);
    }

    /**
     * Verifica se il generatore è stato inizializzato
     *
     * @return true se inizializzato e pronto all'uso
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Ottiene statistiche del generatore per monitoring
     *
     * @return JsonObject con statistiche correnti
     */
    public static JsonObject getStats() {
        JsonObject stats = new JsonObject();
        stats.addProperty("initialized", initialized);
        stats.addProperty("currentId", getCurrentId());
        stats.addProperty("nextId", getNextIdPreview());
        stats.addProperty("totalGenerated", getCurrentId()); // Assumendo start da 1
        return stats;
    }

    // === METODI PRIVATI ===

    /**
     * Trova l'orderID massimo presente nel file storico
     *
     * @return ID massimo trovato, o 0 se nessun ordine presente
     * @throws IOException se errori nella lettura del file
     */
    private static int findMaxOrderIdFromFile() throws IOException {
        try (FileReader reader = new FileReader(STORICO_FILE)) {
            JsonObject rootObject = JsonParser.parseReader(reader).getAsJsonObject();

            if (!rootObject.has("trades")) {
                System.out.println("[OrderIdGenerator] File storico senza sezione 'trades' - inizializzo da 1");
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

            System.out.println("[OrderIdGenerator] Trovati " + trades.size() + " record, orderID massimo: " + maxOrderId);
            return maxOrderId;

        } catch (Exception e) {
            System.err.println("[OrderIdGenerator] Errore lettura file storico: " + e.getMessage());
            throw new IOException("Impossibile leggere file storico", e);
        }
    }
}
