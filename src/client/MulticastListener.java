package client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

//Thread listener per ricevere notifiche multicast dal server CROSS
public class MulticastListener implements Runnable {

    // Configurazione multicast
    private final String multicastAddress;
    private final int multicastPort;

    // Socket multicast per ricezione messaggi
    private MulticastSocket multicastSocket;
    private InetAddress group;

    // Flag per controllo esecuzione thread
    private volatile boolean running;

    // Username dell'utente per filtrare notifiche pertinenti
    private final String username;

    //Costruttore del listener multicast
    public MulticastListener(String multicastAddress, int multicastPort, String username) {
        this.multicastAddress = multicastAddress;
        this.multicastPort = multicastPort;
        this.username = username;
        this.running = false;
    }

    //Avvia il listener multicast
    public void start() throws Exception {
        try {
            // Crea MulticastSocket sulla porta specifica seguendo le slide
            multicastSocket = new MulticastSocket(multicastPort);

            // Ottiene riferimento al gruppo multicast
            group = InetAddress.getByName(multicastAddress);

            // Si unisce al gruppo multicast
            multicastSocket.joinGroup(group);

            running = true;

            System.out.println("[MulticastListener] Listener avviato - Gruppo: " +
                    multicastAddress + ":" + multicastPort);
            System.out.println("[MulticastListener] In ascolto per notifiche prezzo utente: " + username);

        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore avvio listener multicast: " + e.getMessage());
            throw e;
        }
    }

    //ascolta messaggi multicast in continuazione
    public void run() {
        byte[] buffer = new byte[1024]; // Buffer per messaggi multicast

        System.out.println("[MulticastListener] Thread listener avviato");

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Prepara pacchetto per ricezione
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // Riceve messaggio multicast (bloccante)
                multicastSocket.receive(packet);

                // Estrae messaggio dal pacchetto
                String receivedMessage = new String(packet.getData(), 0, packet.getLength(), "UTF-8");

                // Processa il messaggio ricevuto
                processMulticastMessage(receivedMessage);

            } catch (Exception e) {
                if (running) {
                    System.err.println("[MulticastListener] Errore ricezione multicast: " + e.getMessage());
                }
            }
        }

        System.out.println("[MulticastListener] Thread listener terminato");
    }

    //Processa un messaggio multicast ricevuto dal server
    private void processMulticastMessage(String jsonMessage) {
        try {
            // Parsing JSON del messaggio multicast
            JsonObject notification = JsonParser.parseString(jsonMessage).getAsJsonObject();

            // Controllo tipo messaggio
            if (!notification.has("type") || !"priceThreshold".equals(notification.get("type").getAsString())) {
                return; // Ignora messaggi di tipo diverso
            }

            // Estrazione dati notifica
            String notificationUsername = notification.get("username").getAsString();
            int thresholdPrice = notification.get("thresholdPrice").getAsInt();
            int currentPrice = notification.get("currentPrice").getAsInt();
            String message = notification.get("message").getAsString();

            // Filtra solo notifiche per questo utente
            if (username.equals(notificationUsername)) {
                displayPriceNotification(thresholdPrice, currentPrice, message);
            }

        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore processing messaggio multicast: " + e.getMessage());
            System.err.println("[MulticastListener] Messaggio ricevuto: " + jsonMessage);
        }
    }

    //Mostra notifica di soglia prezzo all'utente
    private void displayPriceNotification(int thresholdPrice, int currentPrice, String message) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("NOTIFICA PREZZO BTC");
        System.out.println("=".repeat(60));
        System.out.println("Messaggio: " + message);
        System.out.println("Soglia registrata: " + formatPrice(thresholdPrice) + " USD");
        System.out.println("Prezzo corrente: " + formatPrice(currentPrice) + " USD");
        System.out.println("Utente: " + username);
        System.out.println("=".repeat(60));
        System.out.print(">> "); // Ripristina prompt per input utente
    }

    //Ferma il listener multicast in modo pulito
    public void stop() {
        System.out.println("[MulticastListener] Fermando listener multicast...");

        running = false;

        try {
            if (multicastSocket != null) {
                // Abbandona il gruppo multicast
                if (group != null) {
                    multicastSocket.leaveGroup(group);
                }

                // Chiude socket multicast
                multicastSocket.close();

                System.out.println("[MulticastListener] Listener multicast fermato");
            }
        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore durante stop listener: " + e.getMessage());
        }
    }

    //Formatta prezzo in millesimi per display user-friendly
    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    //Verifica se il listener è attualmente in esecuzione
    public boolean isRunning() {
        return running;
    }
}