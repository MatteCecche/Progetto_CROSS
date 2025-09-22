package server.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * Gestisce i calcoli relativi ai prezzi nel sistema CROSS
 *
 * Responsabilità:
 * - Formattazione prezzi e size per display utente
 * - Calcolo OHLC (Open, High, Low, Close) da lista trade giornalieri
 * - Validazioni logiche per Stop Orders vs prezzo corrente
 * - Utility per manipolazione dati di prezzo
 *
 * Estratto da OrderManager per principio Single Responsibility
 * Thread-safe per uso in ambiente multithreaded del server
 */
public class PriceCalculator {

    // === FORMATTAZIONE DISPLAY ===

    /**
     * Formatta prezzo da millesimi USD a formato leggibile
     * Es: 58000000 -> "58,000" (rappresenta 58.000 USD)
     *
     * @param priceInMilliths prezzo in millesimi di USD
     * @return stringa formattata per display
     */
    public static String formatPrice(int priceInMilliths) {
        return String.format("%,.0f", priceInMilliths / 1000.0);
    }

    /**
     * Formatta size da millesimi BTC a formato leggibile
     * Es: 1000 -> "1.000" (rappresenta 1.000 BTC)
     *
     * @param sizeInMilliths size in millesimi di BTC
     * @return stringa formattata per display con 3 decimali
     */
    public static String formatSize(int sizeInMilliths) {
        return String.format("%.3f", sizeInMilliths / 1000.0);
    }

    // === CALCOLO OHLC PER STORICO PREZZI ===

    /**
     * Calcola OHLC (Open, High, Low, Close) per un singolo giorno
     * A partire da una lista di trade dello stesso giorno
     *
     * @param dayTrades lista di trade JsonObject del giorno
     * @param date data in formato "YYYY-MM-DD"
     * @return JsonObject con statistiche OHLC secondo specifiche ALLEGATO 1
     */
    public static JsonObject calculateDayOHLC(List<JsonObject> dayTrades, String date) {
        if (dayTrades == null || dayTrades.isEmpty()) {
            // Giorno senza trade - ritorna valori vuoti/null
            JsonObject emptyDay = new JsonObject();
            emptyDay.addProperty("date", date);
            emptyDay.addProperty("openPrice", 0);
            emptyDay.addProperty("highPrice", 0);
            emptyDay.addProperty("lowPrice", 0);
            emptyDay.addProperty("closePrice", 0);
            emptyDay.addProperty("volume", 0);
            emptyDay.addProperty("tradesCount", 0);
            emptyDay.addProperty("bidTrades", 0);
            emptyDay.addProperty("askTrades", 0);
            return emptyDay;
        }

        // Ordina trade per timestamp (primo = open, ultimo = close)
        dayTrades.sort((a, b) -> Long.compare(
                a.get("timestamp").getAsLong(),
                b.get("timestamp").getAsLong()
        ));

        // Calcolo OHLC
        int openPrice = dayTrades.get(0).get("price").getAsInt();  // Primo trade
        int closePrice = dayTrades.get(dayTrades.size() - 1).get("price").getAsInt();  // Ultimo trade

        int highPrice = dayTrades.stream()
                .mapToInt(trade -> trade.get("price").getAsInt())
                .max().orElse(openPrice);

        int lowPrice = dayTrades.stream()
                .mapToInt(trade -> trade.get("price").getAsInt())
                .min().orElse(openPrice);

        // Calcolo volume totale e conteggi
        int totalVolume = dayTrades.stream()
                .mapToInt(trade -> trade.get("size").getAsInt())
                .sum();

        // Conteggio trade per tipo (se disponibile)
        long bidTrades = dayTrades.stream()
                .filter(trade -> trade.has("type") && "bid".equals(trade.get("type").getAsString()))
                .count();

        long askTrades = dayTrades.stream()
                .filter(trade -> trade.has("type") && "ask".equals(trade.get("type").getAsString()))
                .count();

        // Costruisce JsonObject risultato secondo specifiche ALLEGATO 1
        JsonObject dayData = new JsonObject();
        dayData.addProperty("date", date);
        dayData.addProperty("openPrice", openPrice);     // Prezzo di apertura
        dayData.addProperty("highPrice", highPrice);     // Prezzo massimo
        dayData.addProperty("lowPrice", lowPrice);       // Prezzo minimo
        dayData.addProperty("closePrice", closePrice);   // Prezzo di chiusura
        dayData.addProperty("volume", totalVolume);      // Volume totale scambiato
        dayData.addProperty("tradesCount", dayTrades.size());
        dayData.addProperty("bidTrades", (int)bidTrades);
        dayData.addProperty("askTrades", (int)askTrades);

        return dayData;
    }

    // === VALIDAZIONI STOP ORDERS ===

    /**
     * Determina se un prezzo di stop è valido rispetto al prezzo corrente
     * Logica Stop Orders:
     * - Stop BID (buy): attivato quando prezzo >= stopPrice (compra quando sale)
     * - Stop ASK (sell): attivato quando prezzo <= stopPrice (vendi quando scende)
     *
     * @param type "bid" per acquisto, "ask" per vendita
     * @param stopPrice prezzo di attivazione in millesimi USD
     * @param currentPrice prezzo corrente di mercato in millesimi USD
     * @return true se stopPrice è logicamente corretto
     */
    public static boolean isValidStopPrice(String type, int stopPrice, int currentPrice) {
        if ("bid".equals(type)) {
            // Stop Buy: deve essere maggiore del prezzo corrente
            // (compro solo quando il prezzo sale oltre la soglia)
            return stopPrice > currentPrice;
        } else if ("ask".equals(type)) {
            // Stop Sell: deve essere minore del prezzo corrente
            // (vendo solo quando il prezzo scende sotto la soglia)
            return stopPrice < currentPrice;
        }

        return false; // Tipo non riconosciuto
    }

    // === UTILITY ANALISI PREZZI ===

    /**
     * Verifica se due prezzi sono considerati "significativamente diversi"
     * Per log delle variazioni di prezzo importanti
     *
     * @param oldPrice prezzo precedente
     * @param newPrice prezzo nuovo
     * @param thresholdPercent soglia percentuale (es: 1.0 = 1%)
     * @return true se variazione supera la soglia
     */
    public static boolean isSignificantPriceChange(int oldPrice, int newPrice, double thresholdPercent) {
        if (oldPrice == 0) return true; // Prima inizializzazione

        double percentChange = Math.abs((double)(newPrice - oldPrice) / oldPrice) * 100;
        return percentChange >= thresholdPercent;
    }

    /**
     * Calcola la percentuale di variazione tra due prezzi
     *
     * @param fromPrice prezzo iniziale
     * @param toPrice prezzo finale
     * @return percentuale di variazione (positiva se aumento, negativa se diminuzione)
     */
    public static double calculatePriceChangePercent(int fromPrice, int toPrice) {
        if (fromPrice == 0) return 0.0;
        return ((double)(toPrice - fromPrice) / fromPrice) * 100;
    }
}
