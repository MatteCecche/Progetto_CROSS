package server.utility;

import com.google.gson.JsonObject;

import java.util.List;

/*
 * Calcoli e formattazioni per i prezzi
 */
public class PriceCalculator {

    public static String formatPrice(int priceInMilliths) {
        return String.format("%,.0f", priceInMilliths / 1000.0);
    }

    public static String formatSize(int sizeInMilliths) {
        return String.format("%.3f", sizeInMilliths / 1000.0);
    }

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

        dayTrades.sort((a, b) -> Long.compare(a.get("timestamp").getAsLong(), b.get("timestamp").getAsLong()));

        int openPrice = dayTrades.get(0).get("price").getAsInt();

        int closePrice = dayTrades.get(dayTrades.size() - 1).get("price").getAsInt();

        int highPrice = dayTrades.stream().mapToInt(trade -> trade.get("price").getAsInt()).max().orElse(openPrice);

        int lowPrice = dayTrades.stream().mapToInt(trade -> trade.get("price").getAsInt()).min().orElse(openPrice);

        int totalVolume = dayTrades.stream().mapToInt(trade -> trade.get("size").getAsInt()).sum();

        long bidTrades = dayTrades.stream().filter(trade -> trade.has("type") && "bid".equals(trade.get("type").getAsString())).count();

        long askTrades = dayTrades.stream().filter(trade -> trade.has("type") && "ask".equals(trade.get("type").getAsString())).count();

        JsonObject dayData = new JsonObject();
        dayData.addProperty("date", date);
        dayData.addProperty("openPrice", openPrice);
        dayData.addProperty("highPrice", highPrice);
        dayData.addProperty("lowPrice", lowPrice);
        dayData.addProperty("closePrice", closePrice);
        dayData.addProperty("volume", totalVolume);
        dayData.addProperty("tradesCount", dayTrades.size());
        dayData.addProperty("bidTrades", (int)bidTrades);
        dayData.addProperty("askTrades", (int)askTrades);

        return dayData;
    }

    public static boolean isValidStopPrice(String type, int stopPrice, int currentPrice) {
        if ("bid".equals(type)) {
            return stopPrice > currentPrice;
        } else if ("ask".equals(type)) {
            return stopPrice < currentPrice;
        }
        return false;
    }
}