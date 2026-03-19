package br.fei.cc7261;

public class Main {
    public static void main(String[] args) throws Exception {
        String mode = System.getenv("APP_MODE");
        if (mode == null) mode = args.length > 0 ? args[0] : "cliente";
        System.out.println("[MAIN] Iniciando modo: " + mode);
        if ("servidor".equalsIgnoreCase(mode)) {
            Servidor.main(args);
        } else {
            Cliente.main(args);
        }
    }
}
