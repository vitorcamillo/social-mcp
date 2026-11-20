package io.github.vitorcamillo.socialmcp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed da guarda de navegacao no BrowserPool: se a instalacao falhar
 * (erro ou timeout), a sessao e fechada e a falha propaga — navegador sem
 * interceptacao pre-requisicao nunca e devolvido para uso. Nada aqui lanca
 * navegador: instalarGuardaOuFechar e testado com Runnables.
 */
class BrowserPoolGuardTest {

    @Test
    void falhaNaInstalacaoFechaSessaoEPropaga() {
        AtomicBoolean fechada = new AtomicBoolean(false);
        IllegalStateException falha = new IllegalStateException("timeout instalando a guarda");
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                BrowserPool.instalarGuardaOuFechar(() -> { throw falha; }, () -> fechada.set(true)));
        assertSame(falha, e, "propaga a falha original da instalacao");
        assertTrue(fechada.get(), "sessao sem guarda deve ser fechada (quit)");
    }

    @Test
    void instalacaoComSucessoNaoFechaSessao() {
        AtomicBoolean instalada = new AtomicBoolean(false);
        AtomicBoolean fechada = new AtomicBoolean(false);
        BrowserPool.instalarGuardaOuFechar(() -> instalada.set(true), () -> fechada.set(true));
        assertTrue(instalada.get());
        assertFalse(fechada.get(), "guarda instalada: sessao segue aberta para uso");
    }

    @Test
    void falhaAoFecharNaoMascaraFalhaDaInstalacao() {
        IllegalStateException falha = new IllegalStateException("guarda nao instalada");
        RuntimeException erroQuit = new RuntimeException("quit falhou");
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                BrowserPool.instalarGuardaOuFechar(() -> { throw falha; }, () -> { throw erroQuit; }));
        assertSame(falha, e, "falha da instalacao continua sendo a causa propagada");
        assertEquals(1, e.getSuppressed().length);
        assertSame(erroQuit, e.getSuppressed()[0], "erro ao fechar entra como suprimido");
    }
}
