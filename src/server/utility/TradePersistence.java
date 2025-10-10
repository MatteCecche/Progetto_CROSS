package server.utility;

import com.google.gson.*;
import server.OrderManager.Order;  // Import della classe Order

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Gestisce la persistenza dei trade nel file StoricoOrdini.json
 * Ogni trade eseguito genera DUE record separati (uno per bid, uno per ask)
 * compatibili con il formato JSON esistente
 */
public class TradePersistence {

    // File storico unificato per tutti i trade
    private static final String STORICO_FILE = "data/StoricoOrdini.json";

    // Lock per sincronizzazione accesso concorrente al file
    private static final ReentrantReadWriteLock ordersLock = new ReentrantReadWriteLock();

    // Configurazione Gson per JSON formattato
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * Inizializza il sistema di persistenza
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
            JsonArray emptyTrades = new JsonArray();
            saveTrades(emptyTrades);
        } else {
            // Verifica integrità del file
            try {
                JsonArray trades = loadTrades();
                System.out.println("[TradePersistence] File storico caricato: " + trades.size() + " record");
            } catch (Exception e) {
                System.err.println("[TradePersistence] Errore verifica file: " + e.getMessage());
            }
        }
    }

    /**
     * Carica tutti i trade dal file storico
     */
    public static JsonArray loadTrades() throws IOException {
        ordersLock.readLock().lock();
        try {
            File storicoFile = new File(STORICO_FILE);

            if (!storicoFile.exists()) {
                System.out.println("[TradePersistence] File non trovato");
                return new JsonArray();
            }

            try (FileReader reader = new FileReader(STORICO_FILE)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                if (root.has("trades") && root.get("trades").isJsonArray()) {
                    return root.getAsJsonArray("trades");
                } else {
                    System.out.println("[TradePersistence] Struttura 'trades' mancante");
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
     */
    public static void saveTrades(JsonArray trades) throws IOException {
        ordersLock.writeLock().lock();
        try {
            JsonObject root = new JsonObject();
            root.add("trades", trades);
            try (FileWriter writer = new FileWriter(STORICO_FILE)) {
                gson.toJson(root, writer);
                writer.flush();
            }

            System.out.println("[TradePersistence] Salvati " + trades.size() + " trade in " + STORICO_FILE);

        } finally {
            ordersLock.writeLock().unlock();
        }
    }

    /**
     * Aggiunge un singolo trade al file storico
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
     *
     * IMPORTANTE: Ogni trade genera DUE record separati:
     * - Un record per il bid order
     * - Un record per l'ask order
     *
     * Formato conforme a storicoOrdini.json esistente:
     * {"orderId": INT, "type": "bid"/"ask", "orderType": "market"/"limit"/"stop",
     *  "size": INT, "price": INT, "timestamp": LONG}
     *
     * @param bidOrder L'ordine di acquisto coinvolto nel trade
     * @param askOrder L'ordine di vendita coinvolto nel trade
     * @param size La quantità scambiata (in millesimi di BTC)
     * @param price Il prezzo di esecuzione (in millesimi di USD)
     */
    public static void saveExecutedTrade(Order bidOrder, Order askOrder, int size, int price) throws IOException {
        try {
            long timestamp = System.currentTimeMillis() / 1000; // UNIX timestamp condiviso

            // Record per il BID order (acquirente)
            JsonObject bidTrade = new JsonObject();
            bidTrade.addProperty("orderId", bidOrder.getOrderId());
            bidTrade.addProperty("type", "bid");
            bidTrade.addProperty("orderType", bidOrder.getOrderType());
            bidTrade.addProperty("size", size);
            bidTrade.addProperty("price", price);
            bidTrade.addProperty("timestamp", timestamp);

            // Record per l'ASK order (venditore)
            JsonObject askTrade = new JsonObject();
            askTrade.addProperty("orderId", askOrder.getOrderId());
            askTrade.addProperty("type", "ask");
            askTrade.addProperty("orderType", askOrder.getOrderType());
            askTrade.addProperty("size", size);
            askTrade.addProperty("price", price);
            askTrade.addProperty("timestamp", timestamp);

            // Salva entrambi i record
            addTrade(bidTrade);
            addTrade(askTrade);

            System.out.println("[TradePersistence] Trade salvato: " +
                    formatSize(size) + " BTC @ " + formatPrice(price) + " USD " +
                    "(Bid: " + bidOrder.getOrderId() + " [" + bidOrder.getUsername() + "], " +
                    "Ask: " + askOrder.getOrderId() + " [" + askOrder.getUsername() + "])");

        } catch (Exception e) {
            System.err.println("[TradePersistence] Errore salvataggio trade eseguito: " + e.getMessage());
            throw new IOException("Impossibile salvare trade eseguito", e);
        }
    }

    /**
     * Conta il numero totale di trade nel file
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

    /**
     * Formatta prezzo in millesimi di USD
     */
    private static String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    /**
     * Formatta size in millesimi di BTC
     */
    private static String formatSize(int sizeInMillis) {
        return String.format("%.3f", sizeInMillis / 1000.0);
    }

    /**
     * Ottiene il path assoluto del file storico
     */
    public static String getStoricoFilePath() {
        return new File(STORICO_FILE).getAbsolutePath();
    }
}