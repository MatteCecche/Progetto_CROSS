package server.utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import server.OrderManager.Order;

/*
 * Gestisce il salvataggio dei trade nel file StoricoOrdini.json
 */
public class TradePersistence {

    // File storico con tutti i trade
    private static final String STORICO_FILE = "data/StoricoOrdini.json";

    // Lock per sincronizzazione accesso al file
    private static final ReentrantReadWriteLock ordersLock = new ReentrantReadWriteLock();

    // Configurazione JSON
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[0m";

    public static void initialize() throws IOException {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            throw new IOException(ANSI_RED + "[TradePersistence] Cartella 'data' non trovata\n" + ANSI_RESET);
        }

        File storicoFile = new File(STORICO_FILE);
        if (!storicoFile.exists()) {
            JsonArray emptyTrades = new JsonArray();
            saveTrades(emptyTrades);
        }
    }

    public static JsonArray loadTrades() throws IOException {
        ordersLock.readLock().lock();
        try {
            File storicoFile = new File(STORICO_FILE);

            if (!storicoFile.exists()) {
                return new JsonArray();
            }

            try (FileReader reader = new FileReader(STORICO_FILE)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                if (root.has("trades") && root.get("trades").isJsonArray()) {
                    return root.getAsJsonArray("trades");
                } else {
                    return new JsonArray();
                }

            } catch (IOException e) {
                throw new IOException(ANSI_RED + "[TradePersistence] Errore lettura file: " + e.getMessage() + ANSI_RESET, e);
            } catch (Exception e) {
                throw new IOException(ANSI_RED + "[TradePersistence] File JSON malformato: " + e.getMessage() + ANSI_RESET, e);
            }
        } finally {
            ordersLock.readLock().unlock();
        }
    }

    public static void saveTrades(JsonArray trades) throws IOException {
        ordersLock.writeLock().lock();
        try {
            JsonObject root = new JsonObject();
            root.add("trades", trades);

            try (FileWriter writer = new FileWriter(STORICO_FILE)) {
                gson.toJson(root, writer);
            }

        } finally {
            ordersLock.writeLock().unlock();
        }
    }

    // Salva un trade eseguito: genera DUE record (uno per bid, uno per ask)
    public static void saveExecutedTrade(Order bidOrder, Order askOrder, int size, int price) throws IOException {
        try {
            long timestamp = System.currentTimeMillis() / 1000;

            // Crea i due trade
            JsonObject bidTrade = new JsonObject();
            bidTrade.addProperty("orderId", bidOrder.getOrderId());
            bidTrade.addProperty("type", "bid");
            bidTrade.addProperty("orderType", bidOrder.getOrderType());
            bidTrade.addProperty("size", size);
            bidTrade.addProperty("price", price);
            bidTrade.addProperty("timestamp", timestamp);

            JsonObject askTrade = new JsonObject();
            askTrade.addProperty("orderId", askOrder.getOrderId());
            askTrade.addProperty("type", "ask");
            askTrade.addProperty("orderType", askOrder.getOrderType());
            askTrade.addProperty("size", size);
            askTrade.addProperty("price", price);
            askTrade.addProperty("timestamp", timestamp);

            JsonArray allTrades = loadTrades();

            allTrades.add(bidTrade);
            allTrades.add(askTrade);

            saveTrades(allTrades);

        } catch (Exception e) {
            throw new IOException(ANSI_RED + "[TradePersistence] Impossibile salvare trade" + ANSI_RESET, e);
        }
    }
}