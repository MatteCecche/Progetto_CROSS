package server.utility;

import com.google.gson.*;
import server.OrderManager.Order;  // Import della classe Order
import server.utility.OrderIdGenerator;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Gestisce la persistenza dei trade nel file StoricoOrdini.json
 *
 * Responsabilità:
 * - Lettura/scrittura thread-safe del file storico
 * - Mantenimento formato JSON strutturato {"trades": [...]}
 * - Formattazione pretty-print per leggibilità
 * - Gestione completa del ciclo vita dei dati trade
 *
 * Estratto da OrderManager per principio Single Responsibility
 * Thread-safe per uso in ambiente multithreaded del server
 */
public class TradePersistence {

    // File storico unificato per tutti i trade
    private static final String STORICO_FILE = "data/StoricoOrdini.json";

    // Lock per sincronizzazione accesso concorrente al file
    private static final ReentrantReadWriteLock ordersLock = new ReentrantReadWriteLock();

    // Configurazione Gson per JSON formattato
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()        // Formattazione con indentazione
            .disableHtmlEscaping()      // Non escape caratteri HTML
            .create();

    /**
     * Inizializza il sistema di persistenza
     * Crea directory e file se non esistenti
     *
     * @throws IOException se errori nella creazione della struttura
     */
    public static void initialize() throws IOException {
        // Crea directory data se non esiste
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            System.out.println("[TradePersistence] Creata directory data/");
        }

        // Verifica/crea file storico
        File storicoFile = new File(STORICO_FILE);
        if (!storicoFile.exists()) {
            // Crea file vuoto con struttura corretta
            System.out.println("[TradePersistence] Creazione file " + STORICO_FILE);
            JsonArray emptyTrades = new JsonArray();
            saveTrades(emptyTrades);
            System.out.println("[TradePersistence] ✅ File storico inizializzato");
        } else {
            System.out.println("[TradePersistence] ✅ File storico esistente: " + STORICO_FILE);

            // Verifica integrità del file
            try {
                JsonArray trades = loadTrades();
                System.out.println("[TradePersistence] ✅ File valido con " + trades.size() + " record");
            } catch (Exception e) {
                System.err.println("[TradePersistence] ⚠️ Errore verifica file: " + e.getMessage());
            }
        }
    }

    /**
     * Carica tutti i trade dal file storico
     * Thread-safe con lock di lettura per permettere accessi concorrenti
     *
     * @return JsonArray con tutti i trade, mai null
     * @throws IOException se errori critici nella lettura
     */
    public static JsonArray loadTrades() throws IOException {
        ordersLock.readLock().lock();
        try {
            File storicoFile = new File(STORICO_FILE);

            if (!storicoFile.exists()) {
                System.out.println("[TradePersistence] File non trovato, ritorno array vuoto");
                return new JsonArray();
            }

            try (FileReader reader = new FileReader(STORICO_FILE)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                if (root.has("trades") && root.get("trades").isJsonArray()) {
                    return root.getAsJsonArray("trades");
                } else {
                    System.out.println("[TradePersistence] Struttura 'trades' mancante, ritorno array vuoto");
                    return new JsonArray();
                }

            } catch (Exception e) {
                System.err.println("[TradePersistence] Errore parsing JSON: " + e.getMessage());
                // Ritorna array vuoto invece di lanciare eccezione
                return new JsonArray();
            }

        } finally {
            ordersLock.readLock().unlock();
        }
    }

    /**
     * Salva tutti i trade nel file storico
     * Thread-safe con lock di scrittura esclusivo
     *
     * @param trades JsonArray con tutti i trade da salvare
     * @throws IOException se errori nella scrittura
     */
    public static void saveTrades(JsonArray trades) throws IOException {
        ordersLock.writeLock().lock();
        try {
            // Struttura unificata del file: {"trades": [...]}
            JsonObject root = new JsonObject();
            root.add("trades", trades);

            // Scrittura con formattazione pretty
            try (FileWriter writer = new FileWriter(STORICO_FILE)) {
                gson.toJson(root, writer);
                writer.flush(); // Assicura che tutto sia scritto su disco
            }

            System.out.println("[TradePersistence] Salvati " + trades.size() + " trade in " + STORICO_FILE);

        } finally {
            ordersLock.writeLock().unlock();
        }
    }

    /**
     * Aggiunge un singolo trade al file storico
     * Metodo ottimizzato: carica → aggiunge → salva
     *
     * @param tradeData JsonObject con i dati del trade
     * @throws IOException se errori nella persistenza
     */
    public static void addTrade(JsonObject tradeData) throws IOException {
        try {
            // Carica tutti i record esistenti
            JsonArray allTrades = loadTrades();

            // Aggiunge il nuovo trade
            allTrades.add(tradeData);

            // Salva tutto insieme
            saveTrades(allTrades);

            System.out.println("[TradePersistence] Trade aggiunto: ID " +
                    (tradeData.has("orderId") ? tradeData.get("orderId").getAsString() : "N/A"));

        } catch (Exception e) {
            System.err.println("[TradePersistence] Errore aggiunta trade: " + e.getMessage());
            throw new IOException("Impossibile aggiungere trade al file storico", e);
        }
    }

    /**
     * Salva un trade eseguito nel formato standard del sistema
     * Crea il JsonObject con tutti i campi necessari e lo persiste
     *
     * @param bidOrder ordine di acquisto del trade
     * @param askOrder ordine di vendita del trade
     * @param size quantità scambiata in millesimi BTC
     * @param price prezzo di esecuzione in millesimi USD
     * @param tradeId ID univoco per il trade (fornito dall'esterno)
     * @throws IOException se errori nella persistenza
     */
    public static void saveExecutedTrade(Order bidOrder, Order askOrder, int size, int price, int tradeId) throws IOException {
        try {
            // Crea record del trade nel formato standard
            JsonObject tradeRecord = new JsonObject();

            // Usa l'ID fornito dall'esterno
            tradeRecord.addProperty("orderId", tradeId);

            // Metadati del trade
            tradeRecord.addProperty("type", "executed");
            tradeRecord.addProperty("orderType", "completed");

            // Dati principali
            tradeRecord.addProperty("size", size);
            tradeRecord.addProperty("price", price);
            tradeRecord.addProperty("timestamp", System.currentTimeMillis() / 1000); // UNIX timestamp

            // Metadati per tracciabilità
            tradeRecord.addProperty("bidOrderId", bidOrder.getOrderId());
            tradeRecord.addProperty("askOrderId", askOrder.getOrderId());
            tradeRecord.addProperty("bidUsername", bidOrder.getUsername());
            tradeRecord.addProperty("askUsername", askOrder.getUsername());

            // Persiste il trade
            addTrade(tradeRecord);

            System.out.println("[TradePersistence] Trade salvato: " +
                    formatSize(size) + " BTC @ " + formatPrice(price) + " USD (ID: " +
                    tradeRecord.get("orderId").getAsInt() + ")");

        } catch (Exception e) {
            System.err.println("[TradePersistence] Errore salvataggio trade eseguito: " + e.getMessage());
            throw new IOException("Impossibile salvare trade eseguito", e);
        }
    }

    /**
     * Conta il numero totale di trade nel file
     *
     * @return numero di trade salvati
     */
    public static int getTotalTradesCount() {
        try {
            JsonArray trades = loadTrades();
            return trades.size();
        } catch (IOException e) {
            System.err.println("[TradePersistence] Errore conteggio trade: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Ottiene statistiche del file storico
     *
     * @return JsonObject con statistiche
     */
    public static JsonObject getStats() {
        JsonObject stats = new JsonObject();

        try {
            File storicoFile = new File(STORICO_FILE);
            JsonArray trades = loadTrades();

            stats.addProperty("fileExists", storicoFile.exists());
            stats.addProperty("filePath", STORICO_FILE);
            stats.addProperty("fileSizeBytes", storicoFile.exists() ? storicoFile.length() : 0);
            stats.addProperty("totalTrades", trades.size());

            if (trades.size() > 0) {
                // Prima e ultima entry
                JsonObject firstTrade = trades.get(0).getAsJsonObject();
                JsonObject lastTrade = trades.get(trades.size() - 1).getAsJsonObject();

                if (firstTrade.has("timestamp")) {
                    stats.addProperty("firstTradeTimestamp", firstTrade.get("timestamp").getAsLong());
                }
                if (lastTrade.has("timestamp")) {
                    stats.addProperty("lastTradeTimestamp", lastTrade.get("timestamp").getAsLong());
                }
            }

        } catch (Exception e) {
            stats.addProperty("error", "Errore generazione statistiche: " + e.getMessage());
        }

        return stats;
    }

    /**
     * Verifica l'integrità del file storico
     *
     * @return true se il file è valido e leggibile
     */
    public static boolean verifyFileIntegrity() {
        try {
            JsonArray trades = loadTrades();
            System.out.println("[TradePersistence] Verifica integrità OK - " + trades.size() + " record");
            return true;
        } catch (Exception e) {
            System.err.println("[TradePersistence] Verifica integrità FALLITA: " + e.getMessage());
            return false;
        }
    }

    // === UTILITY METHODS ===

    /**
     * Formatta prezzo in millesimi per display
     */
    private static String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    /**
     * Formatta size in millesimi per display
     */
    private static String formatSize(int sizeInMillis) {
        return String.format("%.3f", sizeInMillis / 1000.0);
    }

    /**
     * Ottiene il path del file storico
     *
     * @return path assoluto del file
     */
    public static String getStoricoFilePath() {
        return new File(STORICO_FILE).getAbsolutePath();
    }
}