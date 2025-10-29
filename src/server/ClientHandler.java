package server;

import server.utility.ResponseBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import server.utility.UserManager;
import server.utility.Colors;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private BufferedReader in;
    private PrintWriter out;
    private final ConcurrentHashMap<Socket, String> socketUserMap;

    public ClientHandler(Socket clientSocket, ConcurrentHashMap<Socket, String> socketUserMap) {
        this.clientSocket = clientSocket;
        this.socketUserMap = socketUserMap;
    }

    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            out = new PrintWriter(clientSocket.getOutputStream(), true);

            handleClientMessages();

        } catch (SocketTimeoutException e) {
            System.out.println(Colors.RED + "[ClientHandler] Timeout: " + clientSocket.getRemoteSocketAddress() + Colors.RESET);
        } catch (IOException e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore comunicazione: " + e.getMessage() + Colors.RESET);
        } finally {
            cleanup();
        }
    }

    private void handleClientMessages() throws IOException {
        String messageJson;

        while ((messageJson = in.readLine()) != null) {
            try {
                JsonObject request = JsonParser.parseString(messageJson).getAsJsonObject();

                if (!request.has("operation")) {
                    sendError(103, "Manca campo operation");
                    continue;
                }

                String operation = request.get("operation").getAsString();
                JsonObject response = processOperation(operation, request);

                out.println(response);

            } catch (JsonSyntaxException e) {
                sendError(103, "JSON non valido");
            } catch (Exception e) {
                System.err.println(Colors.RED + "[ClientHandler] Errore: " + e.getMessage() + Colors.RESET);
                sendError(103, "Errore interno");
            }
        }
    }

    private JsonObject processOperation(String operation, JsonObject request) {
        switch (operation) {
            case "login":
                return handleLogin(request);
            case "logout":
                return handleLogout(request);
            case "updateCredentials":
                return handleUpdateCredentials(request);
            case "insertLimitOrder":
                return handleInsertLimitOrder(request);
            case "insertMarketOrder":
                return handleInsertMarketOrder(request);
            case "insertStopOrder":
                return handleInsertStopOrder(request);
            case "cancelOrder":
                return handleCancelOrder(request);
            case "getPriceHistory":
                return handleGetPriceHistory(request);
            case "registerPriceAlert":
                return handleRegisterPriceAlert(request);
            default:
                return createError(103, "Operazione non supportata");
        }
    }

    private JsonObject handleLogin(JsonObject request) {
        try {
            if (!request.has("values")) {
                return createError(103, "Manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getString(values, "username");
            String password = getString(values, "password");

            if (!UserManager.validateLoginParams(username, password)) {
                return createError(103, "Parametri non validi");
            }

            if (socketUserMap.containsValue(username)) {
                return ResponseBuilder.Login.userAlreadyLogged();
            }

            if (!UserManager.validateCredentials(username, password)) {
                return ResponseBuilder.Login.wrongCredentials();
            }

            socketUserMap.put(clientSocket, username);

            if (values.has("udpPort")) {
                try {
                    int udpPort = values.get("udpPort").getAsInt();
                    InetAddress clientIP = clientSocket.getInetAddress();
                    UDPNotificationService.registerClient(username, new InetSocketAddress(clientIP, udpPort));
                } catch (Exception e) {
                    System.err.println(Colors.RED + "[ClientHandler] Errore registrazione UDP: " + e.getMessage() + Colors.RESET);
                }
            }

            System.out.println(Colors.GREEN + "[ClientHandler] Login: " + username + Colors.RESET);
            return ResponseBuilder.Login.success();

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore login: " + e.getMessage() + Colors.RESET);
            return createError(103, "Errore interno");
        }
    }

    private JsonObject handleLogout(JsonObject request) {
        try {
            String username = socketUserMap.get(clientSocket);

            if (username == null) {
                return createError(101, "Nessun utente loggato");
            }

            socketUserMap.remove(clientSocket);
            PriceNotificationService.unregisterUser(username);
            UDPNotificationService.unregisterClient(username);

            System.out.println(Colors.GREEN + "[ClientHandler] Logout: " + username + Colors.RESET);
            return ResponseBuilder.Logout.success();

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore logout: " + e.getMessage() + Colors.RESET);
            return createError(101, "Errore interno");
        }
    }

    private JsonObject handleUpdateCredentials(JsonObject request) {
        try {
            if (!request.has("values")) {
                return ResponseBuilder.UpdateCredentials.otherError("Manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getString(values, "username");
            String oldPassword = getString(values, "old_password");
            String newPassword = getString(values, "new_password");

            if (username.isEmpty() || oldPassword.isEmpty() || newPassword.isEmpty()) {
                return ResponseBuilder.UpdateCredentials.otherError("Parametri non validi");
            }

            int result = UserManager.updatePassword(username, oldPassword, newPassword, socketUserMap);

            switch (result) {
                case 100:
                    return ResponseBuilder.UpdateCredentials.success();
                case 101:
                    return ResponseBuilder.UpdateCredentials.invalidPassword();
                case 102:
                    return ResponseBuilder.UpdateCredentials.usernameNotFound();
                case 103:
                    return ResponseBuilder.UpdateCredentials.passwordEqualToOld();
                case 104:
                    return ResponseBuilder.UpdateCredentials.userAlreadyLogged();
                default:
                    return ResponseBuilder.UpdateCredentials.otherError("Errore aggiornamento");
            }

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore updateCredentials: " + e.getMessage() + Colors.RESET);
            return ResponseBuilder.UpdateCredentials.otherError("Errore interno");
        }
    }

    private JsonObject handleInsertLimitOrder(JsonObject request) {
        try {
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return ResponseBuilder.Logout.userNotLogged();
            }

            if (!request.has("values")) {
                return ResponseBuilder.TradingOrder.error();
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getString(values, "type");
            int size = getInt(values, "size");
            int price = getInt(values, "price");

            int orderId = OrderManager.insertLimitOrder(username, type, size, price);

            if (orderId == -1) {
                return ResponseBuilder.TradingOrder.error();
            }

            return ResponseBuilder.TradingOrder.success(orderId);

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore insertLimitOrder: " + e.getMessage() + Colors.RESET);
            return ResponseBuilder.TradingOrder.error();
        }
    }

    private JsonObject handleInsertMarketOrder(JsonObject request) {
        try {
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return ResponseBuilder.TradingOrder.error();
            }

            if (!request.has("values")) {
                return ResponseBuilder.TradingOrder.error();
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getString(values, "type");
            int size = getInt(values, "size");

            int orderId = OrderManager.insertMarketOrder(username, type, size);

            if (orderId == -1) {
                return ResponseBuilder.TradingOrder.error();
            }

            JsonObject response = new JsonObject();
            response.addProperty("orderId", orderId);
            return response;

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore insertMarketOrder: " + e.getMessage() + Colors.RESET);
            return ResponseBuilder.TradingOrder.error();
        }
    }

    private JsonObject handleInsertStopOrder(JsonObject request) {
        try {
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return ResponseBuilder.TradingOrder.error();
            }

            if (!request.has("values")) {
                return ResponseBuilder.TradingOrder.error();
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getString(values, "type");
            int size = getInt(values, "size");
            int stopPrice = getInt(values, "price");

            int orderId = OrderManager.insertStopOrder(username, type, size, stopPrice);

            if (orderId == -1) {
                return ResponseBuilder.TradingOrder.error();
            }

            JsonObject response = new JsonObject();
            response.addProperty("orderId", orderId);
            return response;

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore insertStopOrder: " + e.getMessage() + Colors.RESET);
            return ResponseBuilder.TradingOrder.error();
        }
    }

    private JsonObject handleCancelOrder(JsonObject request) {
        try {
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createError(101, "Utente non loggato");
            }

            if (!request.has("values")) {
                return createError(101, "Manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            int orderId = getInt(values, "orderId");

            int result = OrderManager.cancelOrder(username, orderId);

            if (result == 100) {
                return ResponseBuilder.CancelOrder.success();
            } else {
                return ResponseBuilder.CancelOrder.orderError("Impossibile cancellare ordine");
            }

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore cancelOrder: " + e.getMessage() + Colors.RESET);
            return createError(101, "Errore interno");
        }
    }

    private JsonObject handleGetPriceHistory(JsonObject request) {
        try {
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createError(101, "Utente non loggato");
            }

            if (!request.has("values")) {
                return createError(103, "Manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            if (!values.has("month")) {
                return createError(103, "Manca parametro month");
            }

            String month = getString(values, "month");
            if (month.isEmpty()) {
                return createError(103, "Parametro month vuoto");
            }

            JsonObject historyData = OrderManager.getPriceHistory(month);

            if (historyData.has("error")) {
                return createError(103, historyData.get("error").getAsString());
            }

            return historyData;

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore getPriceHistory: " + e.getMessage() + Colors.RESET);
            return createError(103, "Errore interno");
        }
    }

    private JsonObject handleRegisterPriceAlert(JsonObject request) {
        try {
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createError(101, "Utente non loggato");
            }

            if (!request.has("values")) {
                return createError(103, "Manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            if (!values.has("thresholdPrice")) {
                return createError(103, "Manca thresholdPrice");
            }

            int thresholdPrice;
            try {
                thresholdPrice = values.get("thresholdPrice").getAsInt();
            } catch (Exception e) {
                return createError(103, "thresholdPrice non valido");
            }

            if (thresholdPrice <= 0) {
                return createError(103, "thresholdPrice deve essere > 0");
            }

            int currentPrice = OrderManager.getCurrentMarketPrice();
            if (thresholdPrice <= currentPrice) {
                return createError(103, "thresholdPrice deve essere maggiore del prezzo corrente");
            }

            PriceNotificationService.registerUserForPriceNotifications(username, thresholdPrice);

            JsonObject response = new JsonObject();
            response.addProperty("response", 100);
            response.addProperty("message", "Registrazione completata");
            response.add("multicastInfo", PriceNotificationService.getMulticastInfo());

            return response;

        } catch (Exception e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore registerPriceAlert: " + e.getMessage() + Colors.RESET);
            return createError(103, "Errore interno");
        }
    }

    private String getString(JsonObject json, String key) {
        try {
            return json.has(key) ? json.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private int getInt(JsonObject json, String key) {
        try {
            return json.has(key) ? json.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private JsonObject createError(int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", code);
        response.addProperty("errorMessage", message);
        return response;
    }

    private void sendError(int code, String message) {

        out.println(createError(code, message));
    }

    private void cleanup() {
        try {
            String username = socketUserMap.get(clientSocket);

            if (username != null) {
                PriceNotificationService.unregisterUser(username);
                UDPNotificationService.unregisterClient(username);
                socketUserMap.remove(clientSocket);
                System.out.println(Colors.GREEN + "[ClientHandler] Disconnesso: " + username + Colors.RESET);
            } else {
                System.out.println(Colors.GREEN + "[ClientHandler] Disconnesso: anonimo" + Colors.RESET);
            }

            if (in != null) in.close();
            if (out != null) out.close();
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }

        } catch (IOException e) {
            System.err.println(Colors.RED + "[ClientHandler] Errore cleanup: " + e.getMessage() + Colors.RESET);
        }
    }
}