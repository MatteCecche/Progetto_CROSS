package server.orderbook;

import java.util.*;

import server.OrderManager.Order;

/*
 * Implementa l'algoritmo di matching degli ordini
 */
public class MatchingEngine {

    // Lock statico per sincronizzare operazioni di matching
    private static final Object MATCHING_LOCK = new Object();

    public interface TradeExecutor {
        void executeTrade(Order bidOrder, Order askOrder, int tradeSize, int executionPrice);
    }

    public static int performLimitOrderMatching(TradeExecutor tradeExecutor) {
        synchronized (MATCHING_LOCK) {
            int tradesExecuted = 0;

            while (true) {
                Integer bestBidPrice = OrderBook.getBestBidPrice();
                Integer bestAskPrice = OrderBook.getBestAskPrice();

                if (bestBidPrice == null || bestAskPrice == null || bestBidPrice < bestAskPrice) {
                    break;
                }

                LinkedList<Order> bidList = OrderBook.getBidOrdersAtPrice(bestBidPrice);
                LinkedList<Order> askList = OrderBook.getAskOrdersAtPrice(bestAskPrice);

                if (bidList == null || bidList.isEmpty() || askList == null || askList.isEmpty()) {
                    break;
                }

                Order bidOrder = bidList.getFirst();
                Order askOrder = askList.getFirst();

                int tradeSize = Math.min(bidOrder.getRemainingSize(), askOrder.getRemainingSize());
                int executionPrice = bestAskPrice;

                System.out.println("[MatchingEngine] Match: Order " + bidOrder.getOrderId() + " <-> " + askOrder.getOrderId());

                if (tradeExecutor != null) {
                    tradeExecutor.executeTrade(bidOrder, askOrder, tradeSize, executionPrice);
                }

                tradesExecuted++;

                bidOrder.setRemainingSize(bidOrder.getRemainingSize() - tradeSize);
                askOrder.setRemainingSize(askOrder.getRemainingSize() - tradeSize);

                if (bidOrder.isFullyExecuted()) {
                    bidList.removeFirst();
                    if (bidList.isEmpty()) {
                        OrderBook.getBidOrdersMap().remove(bestBidPrice);
                    }
                }

                if (askOrder.isFullyExecuted()) {
                    askList.removeFirst();
                    if (askList.isEmpty()) {
                        OrderBook.getAskOrdersMap().remove(bestAskPrice);
                    }
                }
            }

            if (tradesExecuted > 0) {
                System.out.println("[MatchingEngine] Eseguiti " + tradesExecuted + " trade");
            }

            return tradesExecuted;
        }
    }

    public static boolean executeMarketOrder(Order marketOrder, TradeExecutor tradeExecutor) {
        if (!"market".equals(marketOrder.getOrderType())) {
            throw new IllegalArgumentException("Solo Market Orders");
        }

        synchronized (MATCHING_LOCK) {
            System.out.println("[MatchingEngine] Esecuzione Market Order " + marketOrder.getOrderId());

            String oppositeType = "bid".equals(marketOrder.getType()) ? "ask" : "bid";
            Map<Integer, LinkedList<Order>> oppositeBook = "ask".equals(oppositeType) ?
                    OrderBook.getAskOrdersMap() : OrderBook.getBidOrdersMap();

            int remainingSize = marketOrder.getRemainingSize();
            int initialSize = remainingSize;

            List<Integer> sortedPrices = getSortedPricesForMarketOrder(oppositeType, oppositeBook);

            for (Integer price : sortedPrices) {
                if (remainingSize <= 0) break;

                LinkedList<Order> ordersAtPrice = oppositeBook.get(price);
                if (ordersAtPrice == null || ordersAtPrice.isEmpty()) {
                    continue;
                }

                Iterator<Order> iterator = ordersAtPrice.iterator();
                while (iterator.hasNext() && remainingSize > 0) {
                    Order oppositeOrder = iterator.next();

                    int tradeSize = Math.min(remainingSize, oppositeOrder.getRemainingSize());

                    Order bidOrder = "bid".equals(marketOrder.getType()) ? marketOrder : oppositeOrder;
                    Order askOrder = "ask".equals(marketOrder.getType()) ? marketOrder : oppositeOrder;

                    if (tradeExecutor != null) {
                        tradeExecutor.executeTrade(bidOrder, askOrder, tradeSize, price);
                    }

                    remainingSize -= tradeSize;
                    oppositeOrder.setRemainingSize(oppositeOrder.getRemainingSize() - tradeSize);

                    if (oppositeOrder.isFullyExecuted()) {
                        iterator.remove();
                    }
                }

                if (ordersAtPrice.isEmpty()) {
                    oppositeBook.remove(price);
                }
            }

            marketOrder.setRemainingSize(remainingSize);

            int executedSize = initialSize - remainingSize;
            boolean fullyExecuted = remainingSize <= 0;

            System.out.println("[MatchingEngine] Market Order eseguito: " + executedSize +
                    " di " + initialSize + (fullyExecuted ? " (completo)" : " (parziale)"));

            return fullyExecuted;
        }
    }

    private static List<Integer> getSortedPricesForMarketOrder(String oppositeType, Map<Integer, LinkedList<Order>> oppositeBook) {
        List<Integer> prices = new ArrayList<Integer>(oppositeBook.keySet());

        if ("ask".equals(oppositeType)) {
            Collections.sort(prices);
        } else {
            Collections.sort(prices, Collections.reverseOrder());
        }

        return prices;
    }
}