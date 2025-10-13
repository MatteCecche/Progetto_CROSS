package server.utility;

import com.google.gson.JsonObject;
import java.util.List;

//Gestisce i calcoli relativi ai prezzi nel sistema CROSS
public class PriceCalculator {

    //Formatta prezzo da millesimi USD a formato leggibile
    public static String formatPrice(int priceInMilliths) {
        return String.format("%,.0f", priceInMilliths / 1000.0);
    }

    //Formatta size da millesimi BTC a formato leggibile
    public static String formatSize(int sizeInMilliths) {
        return String.format("%.3f", sizeInMilliths / 1000.0);
    }

    //Calcola OHLC (Open, High, Low, Close) per un singolo giorno
    public static JsonObject calculateDayOHLC(List<JsonObject> dayTrades, String date) {
        if (dayTrades == null || dayTrades.isEmpty()) {
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

        // Ordina trade per timestamp
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

        // Costruisce JsonObject
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

    //Determina se un prezzo di stop è valido rispetto al prezzo corrente
    public static boolean isValidStopPrice(String type, int stopPrice, int currentPrice) {
        if ("bid".equals(type)) {
            // Stop Buy: deve essere maggiore del prezzo corrente
            return stopPrice > currentPrice;
        } else if ("ask".equals(type)) {
            // Stop Sell: deve essere minore del prezzo corrente
            return stopPrice < currentPrice;
        }

        return false; // Tipo non riconosciuto
    }

    //Verifica se due prezzi sono considerati diversi
    public static boolean isSignificantPriceChange(int oldPrice, int newPrice, double thresholdPercent) {
        if (oldPrice == 0) return true; // Prima inizializzazione

        double percentChange = Math.abs((double)(newPrice - oldPrice) / oldPrice) * 100;
        return percentChange >= thresholdPercent;
    }

    //Calcola la percentuale di variazione tra due prezzi
    public static double calculatePriceChangePercent(int fromPrice, int toPrice) {
        if (fromPrice == 0) return 0.0;
        return ((double)(toPrice - fromPrice) / fromPrice) * 100;
    }
}
