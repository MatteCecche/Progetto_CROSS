package server.orderbook;

import server.OrderManager.Order;
import server.utility.PriceCalculator;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementa l'algoritmo di matching per il sistema di trading CROSS
 * - PRICE Priority: ordini con prezzo migliore hanno precedenza
 * - BID: prezzi più alti hanno precedenza (massima offerta d'acquisto)
 * - ASK: prezzi più bassi hanno precedenza (minima richiesta di vendita)
 * - TIME Priority: a parità di prezzo, ordini più vecchi hanno precedenza
 * - MATCHING: esecuzione quando bestBid >= bestAsk
 */
public class MatchingEngine {

    //Interface per callback di esecuzione trade
    public interface TradeExecutor {
        //Esegue un trade tra due ordini
        void executeTrade(Order bidOrder, Order askOrder, int tradeSize, int executionPrice);
    }

    //Esegue il matching automatico tra ordini bid e ask nell'order book
    public static int performLimitOrderMatching(TradeExecutor tradeExecutor) {
        int tradesExecuted = 0;

        System.out.println("[MatchingEngine] Inizio sessione matching automatico");

        // Continua matching finché possibile (bestBid >= bestAsk)
        while (true) {
            // Ottieni migliori prezzi correnti
            Integer bestBidPrice = OrderBook.getBestBidPrice();
            Integer bestAskPrice = OrderBook.getBestAskPrice();

            // Matching possibile solo se bid >= ask
            if (bestBidPrice == null || bestAskPrice == null || bestBidPrice < bestAskPrice) {
                break; // Nessun matching possibile
            }

            // Ottieni liste ordini ai migliori prezzi
            LinkedList<Order> bidList = OrderBook.getBidOrdersAtPrice(bestBidPrice);
            LinkedList<Order> askList = OrderBook.getAskOrdersAtPrice(bestAskPrice);

            // Verifica validità liste
            if (bidList == null || bidList.isEmpty() || askList == null || askList.isEmpty()) {
                System.err.println("[MatchingEngine] ERRORE: Liste ordini vuote per prezzi " +
                        bestBidPrice + "/" + bestAskPrice);
                break;
            }

            // Ottieni primi ordini dalle liste (time priority)
            Order bidOrder = bidList.peekFirst();
            Order askOrder = askList.peekFirst();

            if (bidOrder == null || askOrder == null) {
                System.err.println("[MatchingEngine] ERRORE: Ordini null nelle liste");
                break;
            }

            // Calcola quantità da scambiare (minimo tra le due)
            int tradeSize = Math.min(bidOrder.getRemainingSize(), askOrder.getRemainingSize());

            // Prezzo di esecuzione: prezzo dell'ordine ASK (maker price)
            // Secondo algoritmo price priority standard
            int executionPrice = bestAskPrice;

            System.out.println("[MatchingEngine] MATCHING: " +
                    "Bid " + bidOrder.getOrderId() + " (" + PriceCalculator.formatSize(bidOrder.getRemainingSize()) + ") vs " +
                    "Ask " + askOrder.getOrderId() + " (" + PriceCalculator.formatSize(askOrder.getRemainingSize()) + ") @ " +
                    PriceCalculator.formatPrice(executionPrice) + " USD");

            // Esegui il trade tramite callback
            if (tradeExecutor != null) {
                tradeExecutor.executeTrade(bidOrder, askOrder, tradeSize, executionPrice);
                tradesExecuted++;
            }

            // Aggiorna remaining size degli ordini
            bidOrder.setRemainingSize(bidOrder.getRemainingSize() - tradeSize);
            askOrder.setRemainingSize(askOrder.getRemainingSize() - tradeSize);

            // Rimuovi ordini completamente eseguiti dalle liste
            if (bidOrder.isFullyExecuted()) {
                bidList.removeFirst();
                System.out.println("[MatchingEngine] Bid Order " + bidOrder.getOrderId() + " completamente eseguito");

                // Rimuovi livello prezzo se vuoto
                if (bidList.isEmpty()) {
                    OrderBook.getBidOrdersMap().remove(bestBidPrice);
                    System.out.println("[MatchingEngine] Rimosso livello BID " + bestBidPrice);
                }
            }

            if (askOrder.isFullyExecuted()) {
                askList.removeFirst();
                System.out.println("[MatchingEngine] Ask Order " + askOrder.getOrderId() + " completamente eseguito");

                // Rimuovi livello prezzo se vuoto
                if (askList.isEmpty()) {
                    OrderBook.getAskOrdersMap().remove(bestAskPrice);
                    System.out.println("[MatchingEngine] Rimosso livello ASK " + bestAskPrice);
                }
            }
        }

        if (tradesExecuted > 0) {
            System.out.println("[MatchingEngine] Sessione matching completata: " +
                    tradesExecuted + " trade eseguiti");
        }

        return tradesExecuted;
    }

    //Esegue un Market Order contro l'order book esistente
    public static boolean executeMarketOrder(Order marketOrder, TradeExecutor tradeExecutor) {
        if (!"market".equals(marketOrder.getOrderType())) {
            throw new IllegalArgumentException("Solo Market Orders possono essere processati da executeMarketOrder()");
        }

        System.out.println("[MatchingEngine] Esecuzione Market Order " + marketOrder.getOrderId() +
                " - " + marketOrder.getType().toUpperCase() + " " +
                PriceCalculator.formatSize(marketOrder.getRemainingSize()) + " BTC");

        // Determina lato opposto dell'order book
        String oppositeType = "bid".equals(marketOrder.getType()) ? "ask" : "bid";
        Map<Integer, LinkedList<Order>> oppositeBook = "ask".equals(oppositeType) ?
                OrderBook.getAskOrdersMap() : OrderBook.getBidOrdersMap();

        int remainingSize = marketOrder.getRemainingSize();
        int initialSize = remainingSize;

        // Ottieni prezzi ordinati ottimalmente per market order
        List<Integer> sortedPrices = getSortedPricesForMarketOrder(oppositeType, oppositeBook);

        // Consuma liquidità livello per livello
        for (Integer price : sortedPrices) {
            if (remainingSize <= 0) break;

            LinkedList<Order> ordersAtPrice = oppositeBook.get(price);
            if (ordersAtPrice == null || ordersAtPrice.isEmpty()) {
                continue; // Salta livelli vuoti (race condition)
            }

            System.out.println("[MatchingEngine] Market Order consuma livello " +
                    PriceCalculator.formatPrice(price) + " USD (" + ordersAtPrice.size() + " ordini)");

            // Consuma tutti gli ordini a questo livello di prezzo
            Iterator<Order> iterator = ordersAtPrice.iterator();
            while (iterator.hasNext() && remainingSize > 0) {
                Order oppositeOrder = iterator.next();

                int tradeSize = Math.min(remainingSize, oppositeOrder.getRemainingSize());

                // Determina ordini per callback (bid sempre primo parametro)
                Order bidOrder = "bid".equals(marketOrder.getType()) ? marketOrder : oppositeOrder;
                Order askOrder = "ask".equals(marketOrder.getType()) ? marketOrder : oppositeOrder;

                System.out.println("[MatchingEngine] Market execution: " +
                        PriceCalculator.formatSize(tradeSize) + " BTC @ " +
                        PriceCalculator.formatPrice(price) + " USD vs Order " + oppositeOrder.getOrderId());

                // Esegui trade al prezzo dell'ordine limite esistente
                if (tradeExecutor != null) {
                    tradeExecutor.executeTrade(bidOrder, askOrder, tradeSize, price);
                }

                // Aggiorna quantità rimanenti
                remainingSize -= tradeSize;
                oppositeOrder.setRemainingSize(oppositeOrder.getRemainingSize() - tradeSize);

                // Rimuovi ordine limite se completamente eseguito
                if (oppositeOrder.isFullyExecuted()) {
                    iterator.remove();
                    System.out.println("[MatchingEngine] Limit Order " + oppositeOrder.getOrderId() +
                            " consumato da Market Order");
                }
            }

            // Rimuovi livello di prezzo se vuoto
            if (ordersAtPrice.isEmpty()) {
                oppositeBook.remove(price);
                System.out.println("[MatchingEngine] Rimosso livello prezzo " + price +
                        " dopo consumo Market Order");
            }
        }

        // Aggiorna remaining size del market order
        marketOrder.setRemainingSize(remainingSize);

        // Risultati esecuzione
        int executedSize = initialSize - remainingSize;
        boolean fullyExecuted = remainingSize <= 0;

        System.out.println("[MatchingEngine] Market Order " + marketOrder.getOrderId() +
                " - Eseguiti: " + PriceCalculator.formatSize(executedSize) + " BTC" +
                (fullyExecuted ? " (COMPLETO)" : " (PARZIALE - rimangono " + PriceCalculator.formatSize(remainingSize) + ")"));

        return fullyExecuted;
    }

    //Ottiene prezzi ordinati ottimalmente per esecuzione Market Order
    private static List<Integer> getSortedPricesForMarketOrder(String oppositeType, Map<Integer, LinkedList<Order>> oppositeBook) {
        if ("ask".equals(oppositeType)) {
            // Market BUY: consuma ASK orders dal prezzo più basso (crescente)
            return oppositeBook.keySet().stream()
                    .sorted(Integer::compareTo)
                    .collect(Collectors.toList());
        } else {
            // Market SELL: consuma BID orders dal prezzo più alto (decrescente)
            return oppositeBook.keySet().stream()
                    .sorted((a, b) -> Integer.compare(b, a))
                    .collect(Collectors.toList());
        }
    }

    //Verifica se è possibile un matching immediato
    public static boolean isMatchingPossible() {
        Integer bestBid = OrderBook.getBestBidPrice();
        Integer bestAsk = OrderBook.getBestAskPrice();

        return bestBid != null && bestAsk != null && bestBid >= bestAsk;
    }

    //Calcola lo spread corrente bid/ask
    public static int getCurrentSpread() {
        Integer bestBid = OrderBook.getBestBidPrice();
        Integer bestAsk = OrderBook.getBestAskPrice();

        if (bestBid != null && bestAsk != null) {
            return bestAsk - bestBid;
        }

        return -1; // Spread non calcolabile
    }

    //Ottiene informazioni sullo stato corrente del matching
    public static Map<String, Object> getMatchingEngineStats() {
        Map<String, Object> stats = new HashMap<>();

        Integer bestBid = OrderBook.getBestBidPrice();
        Integer bestAsk = OrderBook.getBestAskPrice();

        stats.put("bestBid", bestBid);
        stats.put("bestAsk", bestAsk);
        stats.put("spread", getCurrentSpread());
        stats.put("matchingPossible", isMatchingPossible());

        // Calcola depth (liquidità ai migliori prezzi)
        if (bestBid != null) {
            LinkedList<Order> bidOrders = OrderBook.getBidOrdersAtPrice(bestBid);
            int bidDepth = bidOrders != null ? bidOrders.stream().mapToInt(Order::getRemainingSize).sum() : 0;
            stats.put("bestBidDepth", bidDepth);
        }

        if (bestAsk != null) {
            LinkedList<Order> askOrders = OrderBook.getAskOrdersAtPrice(bestAsk);
            int askDepth = askOrders != null ? askOrders.stream().mapToInt(Order::getRemainingSize).sum() : 0;
            stats.put("bestAskDepth", askDepth);
        }

        return stats;
    }

    //Stampa stato corrente del matching engine
    public static void printMatchingEngineStatus() {
        System.out.println("\n=== MATCHING ENGINE STATUS ===");

        Map<String, Object> stats = getMatchingEngineStats();

        Integer bestBid = (Integer) stats.get("bestBid");
        Integer bestAsk = (Integer) stats.get("bestAsk");
        Integer spread = (Integer) stats.get("spread");
        Boolean matchingPossible = (Boolean) stats.get("matchingPossible");

        System.out.println("Best BID: " + (bestBid != null ? PriceCalculator.formatPrice(bestBid) + " USD" : "N/A"));
        System.out.println("Best ASK: " + (bestAsk != null ? PriceCalculator.formatPrice(bestAsk) + " USD" : "N/A"));
        System.out.println("Spread: " + (spread >= 0 ? PriceCalculator.formatPrice(spread) + " USD" : "N/A"));
        System.out.println("Matching possibile: " + matchingPossible);

        if (stats.containsKey("bestBidDepth")) {
            System.out.println("Liquidità BID: " + PriceCalculator.formatSize((Integer) stats.get("bestBidDepth")) + " BTC");
        }
        if (stats.containsKey("bestAskDepth")) {
            System.out.println("Liquidità ASK: " + PriceCalculator.formatSize((Integer) stats.get("bestAskDepth")) + " BTC");
        }

        System.out.println("=============================\n");
    }

    //Verifica che l'order book sia in stato consistente per matching
    public static boolean validateOrderBookConsistency() {
        try {
            // Verifica che non ci siano prezzi che permetterebbero matching immediato
            Integer bestBid = OrderBook.getBestBidPrice();
            Integer bestAsk = OrderBook.getBestAskPrice();

            if (bestBid != null && bestAsk != null && bestBid >= bestAsk) {
                System.err.println("[MatchingEngine] INCONSISTENZA: bestBid (" + bestBid +
                        ") >= bestAsk (" + bestAsk + ") - dovrebbe essere stato matchato");
                return false;
            }

            // Verifica che le liste agli ordini non siano vuote
            for (Integer price : OrderBook.getAllBidPrices()) {
                LinkedList<Order> orders = OrderBook.getBidOrdersAtPrice(price);
                if (orders == null || orders.isEmpty()) {
                    System.err.println("[MatchingEngine] INCONSISTENZA: Livello BID " + price + " vuoto");
                    return false;
                }
            }

            for (Integer price : OrderBook.getAllAskPrices()) {
                LinkedList<Order> orders = OrderBook.getAskOrdersAtPrice(price);
                if (orders == null || orders.isEmpty()) {
                    System.err.println("[MatchingEngine] INCONSISTENZA: Livello ASK " + price + " vuoto");
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            System.err.println("[MatchingEngine] Errore validazione consistenza: " + e.getMessage());
            return false;
        }
    }
}