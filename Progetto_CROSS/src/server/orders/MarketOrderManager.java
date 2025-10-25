package server.orders;

import server.OrderManager.Order;
import server.orderbook.OrderBook;
import server.orderbook.MatchingEngine;

/*
 * Gestisce gli ordini Market
 */
public class MarketOrderManager {

    public static boolean insertMarketOrder(Order order, MatchingEngine.TradeExecutor tradeExecutor) {
        try {
            if (!"market".equals(order.getOrderType())) {
                System.err.println("[MarketOrderManager] Errore: Ordine non è di tipo 'market'");
                return false;
            }

            if (!isValidMarketOrder(order)) {
                System.err.println("[MarketOrderManager] Errore: Parametri non validi");
                return false;
            }

            if (!hasLiquidity(order)) {
                System.err.println("[MarketOrderManager] Errore: Liquidità insufficiente");
                return false;
            }

            System.out.println("[MarketOrderManager] Esecuzione Market Order " + order.getOrderId());

            boolean success = MatchingEngine.executeMarketOrder(order, tradeExecutor);

            if (!success) {
                System.err.println("[MarketOrderManager] Market Order non completamente eseguito - scartato");
                return false;
            }

            System.out.println("[MarketOrderManager] Market Order eseguito completamente");
            return true;

        } catch (Exception e) {
            System.err.println("[MarketOrderManager] Errore: " + e.getMessage());
            return false;
        }
    }

    private static boolean isValidMarketOrder(Order order) {
        if (!isValidOrderType(order.getType())) {
            return false;
        }

        if (order.getSize() <= 0) {
            return false;
        }

        if (order.getPrice() != 0) {
            System.err.println("[MarketOrderManager] Market Order deve avere price = 0");
            return false;
        }

        return true;
    }

    private static boolean isValidOrderType(String type) {
        return "bid".equals(type) || "ask".equals(type);
    }

    private static boolean hasLiquidity(Order order) {
        return OrderBook.hasLiquidity(order.getType(), order.getSize());
    }
}