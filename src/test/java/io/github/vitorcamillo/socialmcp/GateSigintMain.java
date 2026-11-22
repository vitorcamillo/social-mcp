package io.github.vitorcamillo.socialmcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** CLI de mentira para TextGateSigintTest: roda um gate cujo filho grava o pid e fica vivo. */
final class GateSigintMain {
    private GateSigintMain() {}

    public static void main(String[] args) throws Exception {
        Store s = TextGateMutantesTest.storeComGate(Path.of(args[0]),
                "sleep 30 & echo $! > '" + args[1] + "'; wait", 60, null);
        Path lote = s.batchesDir().resolve("x.draft.json");
        Files.writeString(lote, "{\"actions\":[]}", StandardCharsets.UTF_8);
        TextGate.rodarPre(s, lote, List.of());
    }
}
