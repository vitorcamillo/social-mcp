package io.github.vitorcamillo.socialmcp;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Lógica pura (sem navegador) da detecção de posts duplicados. */
public final class Duplicates {
    private Duplicates() {}

    public record Pagina(String url, String texto) {}
    public record Grupo(double similaridade, List<String> urls, List<String> inicios) {}

    private static final java.util.regex.Pattern ID_SEGURO = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * Seletor do item de uma URL listada (receita list_posts.itemTexto): o 1o grupo de idRegex
     * na URL substitui {{id}} no modelo. Null quando a URL nao tem id, o id tem caractere fora de
     * [A-Za-z0-9_-] (nao entra em seletor) ou a configuracao esta incompleta/invalida.
     */
    static String seletorDoItem(String url, String idRegex, String modelo) {
        if (url == null || idRegex == null || idRegex.isBlank() || modelo == null || !modelo.contains("{{id}}")) {
            return null;
        }
        java.util.regex.Matcher m;
        try {
            m = java.util.regex.Pattern.compile(idRegex).matcher(url);
        } catch (java.util.regex.PatternSyntaxException e) {
            return null;
        }
        if (!m.find() || m.groupCount() < 1 || m.group(1) == null) return null;
        String id = m.group(1);
        if (!ID_SEGURO.matcher(id).matches()) return null;
        return modelo.replace("{{id}}", id);
    }

    /** Minimo de palavras para uma pagina entrar na comparacao (elegivel em agrupar). */
    static final int MIN_PALAVRAS_COMPARAVEL = 5;

    static List<String> semInterfaceComum(List<String> textos) {
        int n = textos == null ? 0 : textos.size();
        List<List<String>> linhasPorPagina = new ArrayList<>(n);
        Map<String, Integer> paginasComLinha = new HashMap<>();
        for (String texto : textos == null ? List.<String>of() : textos) {
            String t = texto == null ? "" : texto;
            List<String> linhas = new ArrayList<>();
            Set<String> vistas = new HashSet<>();
            for (String linha : t.split("\\R", -1)) {
                String s = linha.trim();
                if (s.isEmpty()) continue;
                linhas.add(s);
                if (vistas.add(s)) {
                    paginasComLinha.merge(s, 1, Integer::sum);
                }
            }
            linhasPorPagina.add(linhas);
        }
        Set<String> comum = new HashSet<>();
        for (Map.Entry<String, Integer> e : paginasComLinha.entrySet()) {
            // Interface = linha CURTA (rotulo, contador, @ da conta). Linha com
            // MIN_PALAVRAS_COMPARAVEL palavras ou mais e legenda/conteudo: nunca sai, senao duas
            // paginas com a mesma legenda perdem justamente o texto que as torna duplicadas.
            if (contaPalavras(normalizar(e.getKey())) >= MIN_PALAVRAS_COMPARAVEL) continue;
            int count = e.getValue();
            if (count >= Math.min(n, Math.max(4, n / 2 + 1))) comum.add(e.getKey());
        }
        List<String> out = new ArrayList<>(n);
        for (List<String> linhas : linhasPorPagina) {
            List<String> resto = new ArrayList<>();
            for (String s : linhas) {
                if (!comum.contains(s)) resto.add(s);
            }
            out.add(String.join("\n", resto));
        }
        return out;
    }

    static String normalizar(String s) {
        if (s == null) return "";
        String minusculas = s.toLowerCase(Locale.ROOT);
        String semAcento = Normalizer.normalize(minusculas, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String espacos = semAcento.replaceAll("[^\\p{L}\\p{Nd}]+", " ");
        return espacos.replaceAll("\\s+", " ").trim();
    }

    static Set<String> shingles(String normalizado) {
        Set<String> out = new LinkedHashSet<>();
        String t = normalizado == null ? "" : normalizado.trim();
        String[] palavras = t.isEmpty() ? new String[0] : t.split("\\s+");
        if (palavras.length > 0 && palavras.length <= 2) out.add(t);
        for (int i = 0; i + 2 < palavras.length; i++) {
            out.add(palavras[i] + " " + palavras[i + 1] + " " + palavras[i + 2]);
        }
        return out;
    }

    /** Palavras de um texto ja normalizado (mesmo corte de elegibilidade de agrupar). */
    private static int contaPalavras(String normalizado) {
        return normalizado.isEmpty() ? 0 : normalizado.split("\\s+").length;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        boolean aVazio = a == null || a.isEmpty();
        boolean bVazio = b == null || b.isEmpty();
        Set<String> uniao = new HashSet<>(aVazio ? Set.of() : a);
        uniao.addAll(bVazio ? Set.of() : b);
        if (uniao.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(aVazio ? Set.of() : a);
        inter.retainAll(bVazio ? Set.of() : b);
        return (double) inter.size() / uniao.size();
    }

    public static List<Grupo> agrupar(List<Pagina> paginas, double limiar) {
        if (paginas == null || paginas.isEmpty()) return List.of();
        int n = paginas.size();
        List<String> textos = new ArrayList<>(n);
        for (Pagina p : paginas) {
            textos.add(p == null || p.texto() == null ? "" : p.texto());
        }
        List<String> restantes = semInterfaceComum(textos);
        List<Set<String>> shinglesPorPagina = new ArrayList<>(n);
        List<Integer> elegiveis = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String norm = normalizar(restantes.get(i));
            shinglesPorPagina.add(shingles(norm));
            int palavras = contaPalavras(norm);
            if (palavras >= MIN_PALAVRAS_COMPARAVEL) elegiveis.add(i);
        }
        double[][] sim = new double[n][n];
        int[] pai = new int[n];
        for (int i = 0; i < n; i++) pai[i] = i;
        for (int a = 0; a < elegiveis.size(); a++) {
            for (int j : elegiveis.subList(a + 1, elegiveis.size())) {
                int i = elegiveis.get(a);
                double s = jaccard(shinglesPorPagina.get(i), shinglesPorPagina.get(j));
                sim[i][j] = s;
                sim[j][i] = s;
                if (s >= limiar) unir(pai, i, j);
            }
        }
        Map<Integer, List<Integer>> porRaiz = new LinkedHashMap<>();
        for (int i : elegiveis) {
            porRaiz.computeIfAbsent(raiz(pai, i), k -> new ArrayList<>()).add(i);
        }
        List<Grupo> grupos = new ArrayList<>();
        for (List<Integer> membros : porRaiz.values()) {
            if (membros.size() < 2) continue;
            double min = Double.MAX_VALUE;
            for (int a = 0; a < membros.size(); a++) {
                for (int j : membros.subList(a + 1, membros.size())) {
                    double s = sim[membros.get(a)][j];
                    if (s >= limiar) min = Math.min(min, s);
                }
            }
            if (min == Double.MAX_VALUE) continue;
            double arredondada = Math.round(min * 100.0) / 100.0;
            List<String> urls = new ArrayList<>();
            List<String> inicios = new ArrayList<>();
            for (int idx : membros) {
                Pagina p = paginas.get(idx);
                urls.add(p == null || p.url() == null ? "" : p.url());
                String resto = restantes.get(idx).replaceAll("\\R", " ");
                inicios.add(resto.substring(0, Math.min(resto.length(), 120)));
            }
            grupos.add(new Grupo(arredondada, List.copyOf(urls), List.copyOf(inicios)));
        }
        grupos.sort((g1, g2) -> Double.compare(g2.similaridade(), g1.similaridade()));
        return grupos;
    }

    private static int raiz(int[] pai, int i) {
        while (pai[i] != i) {
            pai[i] = pai[pai[i]];
            i = pai[i];
        }
        return i;
    }

    private static void unir(int[] pai, int i, int j) {
        pai[raiz(pai, i)] = raiz(pai, j);
    }
}
