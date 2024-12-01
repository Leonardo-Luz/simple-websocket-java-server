import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSocket {
  private static List<ClientHandler> clients = new ArrayList<>();

  public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
    ServerSocket server = new ServerSocket(8080);
    try {
      System.out.println("Server started on 127.0.0.1:8080\r\nWaiting for a connection...");
      while (true) {
        Socket clientSocket = server.accept();
        System.out.println("A client connected.");
        ClientHandler clientHandler = new ClientHandler(clientSocket);
        Thread clientThread = new Thread(clientHandler);
        clientThread.start();
        synchronized (clients) {
          clients.add(clientHandler);
        }
      }
    } finally {
      server.close();
    }
  }

  private static class ClientHandler implements Runnable {
    private Socket clientSocket;
    private InputStream in;
    private OutputStream out;

    public ClientHandler(Socket socket) throws IOException {
      this.clientSocket = socket;
      this.in = socket.getInputStream();
      this.out = socket.getOutputStream();
    }

    @Override
    public void run() {
      try {
        Scanner scanner = new Scanner(in, "UTF-8");
        try {
          String data = scanner.useDelimiter("\\r\\n\\r\\n").next();
          Matcher get = Pattern.compile("^GET").matcher(data);
          if (get.find()) {
            Matcher match = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);
            match.find();

            try {
              String secWebSocketAccept = Base64.getEncoder().encodeToString(
                  MessageDigest.getInstance("SHA-1")
                      .digest((match.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")));

              String response = "HTTP/1.1 101 Switching Protocols\r\n"
                  + "Connection: Upgrade\r\n"
                  + "Upgrade: websocket\r\n"
                  + "Sec-WebSocket-Accept: " + secWebSocketAccept + "\r\n\r\n";
              out.write(response.getBytes("UTF-8"));
              out.flush();
              System.out.println("Handshake complete");
            } catch (NoSuchAlgorithmException err) {
              err.printStackTrace();
            }

            while (true) {
              byte[] frameHeader = new byte[2];
              int bytesRead = in.read(frameHeader);
              if (bytesRead == -1) {
                break;
              }

              if ((frameHeader[0] & 0x0F) == 0x01) {
                int length = frameHeader[1] & 0x7F;

                if (length == 126) {
                  length = in.read() << 8 | in.read();
                } else if (length == 127) {
                  length = in.read() << 56 | in.read() << 48 | in.read() << 40 | in.read() << 32
                      | in.read() << 24 | in.read() << 16 | in.read() << 8 | in.read();
                }

                byte[] message = new byte[length];

                byte[] maskKey = new byte[4];
                in.read(maskKey);

                in.read(message);

                for (int i = 0; i < length; i++) {
                  message[i] ^= maskKey[i % 4];
                }

                String messageText = new String(message, "UTF-8");
                System.out.println("Received message: " + messageText);

                broadcastMessage(messageText);
              }

            }

          }
        } finally {
          scanner.close();
        }
      } catch (IOException e) {
        System.out.println("Error handling client: " + e.getMessage());
      } finally {
        try {
          clientSocket.close();
          synchronized (clients) {
            clients.remove(this);
            System.out.println("Client Disconnected");
          }
        } catch (IOException e) {
          System.out.println("Error closing client connection: " + e.getMessage());
        }
      }
    }

    private void sendMessage(String message) throws IOException {
      byte[] messageBytes = message.getBytes("UTF-8");
      int length = messageBytes.length;
      byte[] frame;

      if (length < 126) {
        frame = new byte[2 + length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) length;
      }

      else if (length <= 0xFFFF) {
        frame = new byte[4 + length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) 126;
        frame[2] = (byte) (length >> 8);
        frame[3] = (byte) (length & 0xFF);
      }

      else {
        frame = new byte[10 + length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) 127;
        for (int i = 0; i < 8; i++) {
          frame[2 + i] = (byte) (length >> (56 - (i * 8)) & 0xFF);
        }
      }

      System.arraycopy(messageBytes, 0, frame, frame.length - messageBytes.length, messageBytes.length);

      try {
        out.write(frame);
        out.flush();
      } catch (IOException err) {
        System.out.println("Error sending frame");
        err.printStackTrace();
      }

      System.out.println("Message sended to client: " + message);
    }

    private static void broadcastMessage(String message) {
      System.out.println("Clients: " + clients.size());
      synchronized (clients) {
        for (ClientHandler client : clients) {
          try {
            System.out.println("Sending message to client: " + client.clientSocket);
            client.sendMessage(message);
          } catch (IOException e) {
            System.out.println("Error sending message to client: " + e.getMessage());
          }
        }
      }
    }
  }
}
