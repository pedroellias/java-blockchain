PASTA_SRC    = src
PASTA_BIN    = bin
FLAGS        = -Xlint:all -sourcepath $(PASTA_SRC)

ARQUIVOS_SRC = $(wildcard $(PASTA_SRC)/*.java)
ARQUIVOS_OBJ = $(patsubst $(PASTA_SRC)/%.java, $(PASTA_BIN)/%.class, $(ARQUIVOS_SRC))

# $(info ARQUIVOS_SRC = $(ARQUIVOS_SRC))
# $(info ARQUIVOS_OBJ = $(ARQUIVOS_OBJ))

.PHONY: all run clean

all: $(ARQUIVOS_OBJ)

$(PASTA_BIN)/%.class: $(PASTA_SRC)/%.java | $(PASTA_BIN)
	javac $(FLAGS) -d $(PASTA_BIN) $<

$(PASTA_BIN):
	mkdir -p $(PASTA_BIN)

run: all
	java -cp $(PASTA_BIN) Peer $(ARGS)

clean:
	rm -rf $(PASTA_BIN)