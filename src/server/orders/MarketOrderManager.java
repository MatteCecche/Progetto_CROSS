package server.orders;

import server.OrderManager.Order;
import server.orderbook.OrderBook;
import server.orderbook.MatchingEngine;
import server.utility.Colors;

public class MarketOrderManager {

    public static boolean insertMarketOrder(Order order, MatchingEngine.TradeExecutor tradeExecutor) {
        try {
            if (!"market".equals(order.getOrderType())) {
                System.err.println(Colors.RED + "[MarketOrderManager] Errore: Ordine non è di tipo 'market'" + Colors.RESET);
                return false;
            }

            if (!isValidMarketOrder(order)) {
                System.err.println(Colors.RED + "[MarketOrderManager] Errore: Parametri non validi" + Colors.RESET);
                return false;
            }

            if (!hasLiquidity(order)) {
                System.err.println(Colors.RED + "[MarketOrderManager] Errore: Liquidità insufficiente" + Colors.RESET);
                return false;
            }

            System.out.println(Colors.GREEN + "[MarketOrderManager] Esecuzione Market Order " + order.getOrderId() + Colors.RESET);

            boolean success = MatchingEngine.executeMarketOrder(order, tradeExecutor);

            if (!success) {
                System.err.println(Colors.RED + "[MarketOrderManager] Market Order non completamente eseguito - scartato" + Colors.RESET);
                return false;
            }

            System.out.println(Colors.GREEN + "[MarketOrderManager] Market Order eseguito completamente" + Colors.RESET);
            return true;

        } catch (Exception e) {
            System.err.println(Colors.RED + "[MarketOrderManager] Errore: " + e.getMessage() + Colors.RESET);
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
            System.err.println(Colors.RED + "[MarketOrderManager] Market Order deve avere price = 0" + Colors.RESET);
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