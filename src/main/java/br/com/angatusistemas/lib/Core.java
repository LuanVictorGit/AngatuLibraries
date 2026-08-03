package br.com.angatusistemas.lib;

/**
 * Classe de ponto de entrada para testes manuais da biblioteca.
 *
 * <p><strong>Propósito:</strong> servir de harness de execução local — o
 * método {@link #main(String[])} é usado para testar componentes da biblioteca
 * durante o desenvolvimento.</p>
 *
 * <p><strong>Quando usar:</strong> apenas em desenvolvimento interno, rodando
 * via {@code java -cp ... br.com.angatusistemas.lib.Core}.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> não faz parte da API pública da
 * biblioteca; aplicações reais devem usar {@link AngatuLib} como ponto de
 * entrada.</p>
 *
 * <p><strong>Restrição:</strong> classe {@code final} com construtor privado —
 * não deve ser instanciada nem estendida.</p>
 *
 * @author Angatu Sistemas
 * @see AngatuLib
 */
public final class Core {

    private Core() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    /**
     * Ponto de entrada para testes manuais.
     *
     * @param args Argumentos da linha de comando (não utilizados)
     */
    public static void main(String[] args) {
        // TODO aqui roda os testes da biblioteca, testar componentes.
    }

}
