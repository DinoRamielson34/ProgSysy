import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ProxyCacheServer {
    private static String PROXY_IP;
    private static int PORT;
    private static long CACHE_EXPIRATION_TIME;
    private static boolean running = true;
    private static final Map<String, CacheEntry> cacheMemory = new ConcurrentHashMap<>();
    private static ScheduledExecutorService cacheCleaner;

    public static void main(String[] args) {
        loadConfiguration();
        System.out.println("Proxy en écoute sur " + PROXY_IP + ":" + PORT);

        try (ServerSocket serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName(PROXY_IP))) {
            startCacheCleaner();
            new Thread(ProxyCacheServer::handleServerCommands).start();

            while (running) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ProxyHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Erreur serveur : " + e.getMessage());
        } finally {
            stopCacheCleaner();
        }
    }

    private static void loadConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("config.properties")) {
            properties.load(input);
            PROXY_IP = properties.getProperty("proxy.ip");
            PORT = Integer.parseInt(properties.getProperty("proxy.port"));
            CACHE_EXPIRATION_TIME = Long.parseLong(properties.getProperty("cache.expiration"));
            System.out.println("Configuration chargée avec succès.");
        } catch (IOException e) {
            System.err.println("Erreur chargement configuration : " + e.getMessage());
            System.exit(1);
        }
    }

    private static void handleServerCommands() {
    try (Scanner scanner = new Scanner(System.in)) {
        while (running) {
            System.out.print("$ProxyCache > ");
            String command = scanner.nextLine().trim();

            if ("listecache".equalsIgnoreCase(command)) {
                listCacheContents();
            } else if ("exit".equalsIgnoreCase(command)) {
                System.out.println("Arrêt du serveur...");
                running = false;
                System.exit(1);
            } else if (command.startsWith("remove ")) {
                String fileName = command.substring(7).trim();
                if (!fileName.isEmpty()) {
                    removeCacheEntry(fileName);
                } else {
                    System.out.println("Veuillez spécifier le fichier à supprimer.");
                }
            } else {
                System.out.println("Commande non reconnue.");
            }
        }
    }
}


    private static void listCacheContents() {
        if (cacheMemory.isEmpty()) {
            System.out.println("Le cache est vide.");
        } else {
            System.out.println("Contenu du cache :");
            cacheMemory.forEach((key, value) -> System.out.println("- " + key));
        }
    }

    private static void startCacheCleaner() {
        cacheCleaner = Executors.newSingleThreadScheduledExecutor();
        cacheCleaner.scheduleAtFixedRate(() -> {
            cacheMemory.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }, 0, CACHE_EXPIRATION_TIME, TimeUnit.MILLISECONDS);
    }

    private static void stopCacheCleaner() {
        if (cacheCleaner != null) {
            cacheCleaner.shutdown();
            System.out.println("Nettoyeur de cache arrêté.");
        }
    }

    private static void removeCacheEntry(String fileName) {
    if (cacheMemory.containsKey(fileName)) {
        cacheMemory.remove(fileName);
        System.out.println("Fichier '" + fileName + "' supprimé du cache.");
    } else {
        System.out.println("Le fichier '" + fileName + "' n'est pas dans le cache.");
    }
}


    static class CacheEntry {
        private final byte[] content;
        private final long timestamp;

        CacheEntry(byte[] content) {
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRATION_TIME;
        }

        byte[] getContent() {
            return content;
        }
    }

    static class ProxyHandler implements Runnable {
        private final Socket clientSocket;

        ProxyHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 OutputStream clientOut = clientSocket.getOutputStream()) {

                String request = reader.readLine();
                if (request == null || !request.startsWith("GET")) return;

                String fileName = request.split(" ")[1].substring(1);
                if (cacheMemory.containsKey(fileName) && !cacheMemory.get(fileName).isExpired()) {
                    sendResponse(clientOut, 200, "OK", fileName, cacheMemory.get(fileName).getContent());
                } else {
                    byte[] content = fetchFileFromOrigin(fileName);
                    if (content != null) {
                        cacheMemory.put(fileName, new CacheEntry(content));
                        sendResponse(clientOut, 200, "OK", fileName, content);
                    } else {
                        sendResponse(clientOut, 404, "Not Found", null, null);
                    }
                }
            } catch (IOException e) {
                System.err.println("Erreur traitement client : " + e.getMessage());
            }
        }

        private byte[] fetchFileFromOrigin(String fileName) {
            try (Socket originSocket = new Socket("localhost", 80);
                 InputStream originIn = originSocket.getInputStream();
                 OutputStream originOut = originSocket.getOutputStream()) {

                originOut.write(("GET /" + fileName + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes());
                originOut.flush();

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[4096];
                int bytesRead;

                while ((bytesRead = originIn.read(data)) != -1) {
                    buffer.write(data, 0, bytesRead);
                }
                return buffer.toByteArray();
            } catch (IOException e) {
                System.err.println("Erreur récupération fichier : " + e.getMessage());
                return null;
            }
        }

        private void sendResponse(OutputStream clientOut, int statusCode, String statusMessage, String fileName, byte[] content) throws IOException {
            StringBuilder response = new StringBuilder();
            response.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMessage).append("\r\n");

            if (fileName != null) {
                response.append("Content-Type: ").append(getContentType(fileName)).append("\r\n");
            }

            response.append("Cache-Control: max-age=").append(CACHE_EXPIRATION_TIME / 1000).append("\r\n");
            response.append("\r\n");
            clientOut.write(response.toString().getBytes());

            if (content != null) {
                clientOut.write(content);
            }
        }

        private String getContentType(String fileName) {
            if (fileName.endsWith(".html")) return "text/html";
            if (fileName.endsWith(".css")) return "text/css";
            if (fileName.endsWith(".js")) return "application/javascript";
            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
            if (fileName.endsWith(".png")) return "image/png";
            return "application/octet-stream";
        }
    }
}
