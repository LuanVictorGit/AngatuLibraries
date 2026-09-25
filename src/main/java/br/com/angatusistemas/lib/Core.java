package br.com.angatusistemas.lib;

/**
 * Ponto de entrada informativo da biblioteca.
 *
 * <p><strong>Propósito:</strong> a AngatuLibraries não tem nada para executar sozinha. Rodar
 * esta classe ({@code java -cp ... br.com.angatusistemas.lib.Core}) só mostra no console como
 * iniciar uma aplicação e como rodar os testes.</p>
 *
 * <p><strong>Testes:</strong> os testes da biblioteca são automatizados, em JUnit 5, em
 * {@code src/test/java}, e rodam com {@code mvn test} — não por esta classe, que antes era um
 * esboço de teste manual sem conteúdo.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> não faz parte da API de uso da biblioteca;
 * aplicações reais usam {@link AngatuLib} como ponto de entrada.</p>
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
     * Mostra no console como iniciar uma aplicação com a biblioteca e como rodar os testes.
     *
     * @param args Argumentos da linha de comando (não utilizados)
     */
    public static void main(String[] args) {
        System.out.println("""
                AngatuLibraries é uma biblioteca: não há nada para executar sozinha.

                Para iniciar uma aplicação, crie o servidor no método main do projeto
                (o terceiro argumento liga o bloqueio por excesso de requisições):
                    new AngatuLib("localhost", 8080, true);

                Para testar a biblioteca, rode os testes automatizados (JUnit 5, em src/test/java):
                    mvn test""");
    }
}
