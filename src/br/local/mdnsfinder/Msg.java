package br.local.mdnsfinder;

import java.util.Locale;

/** All user-visible texts. English by default, Portuguese when the device language is "pt". */
public enum Msg {
    NAME_LABEL("Device name", "Nome do dispositivo"),
    NAME_HINT("printer.local", "impressora.local"),
    FIND("Find IP address", "Descobrir endereço IP"),
    OPEN("Open in browser", "Abrir no navegador"),
    SEARCHING("Searching…", "Procurando…"),
    FOUND("Found", "Encontrado"),
    NOT_FOUND("No answer. Check the name and make sure this device is on the same Wi-Fi network.",
            "Sem resposta. Confira o nome e se este aparelho está na mesma rede Wi-Fi."),
    NO_NETWORK("No network connection found. Connect to the same Wi-Fi network as the device.",
            "Nenhuma conexão de rede encontrada. Conecte-se à mesma rede Wi-Fi do dispositivo."),
    EMPTY_NAME("Type a device name first.", "Digite o nome do dispositivo primeiro."),
    BAD_NAME("That is not a valid device name.", "Esse não é um nome de dispositivo válido."),
    NO_BROWSER("No browser found.", "Nenhum navegador encontrado."),
    ONLY_LINK_LOCAL("Only an IPv6 link-local address was found; a browser cannot open it.",
            "Só foi encontrado um endereço IPv6 link-local; o navegador não consegue abri-lo.");

    private final String en;
    private final String pt;

    Msg(String en, String pt) {
        this.en = en;
        this.pt = pt;
    }

    public String text() {
        return text("pt".equals(Locale.getDefault().getLanguage()));
    }

    public String text(boolean portuguese) {
        return portuguese ? pt : en;
    }
}
