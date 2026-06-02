OUT_DIR=out
SRC_FILES=$(shell find src/main/java -name "*.java")
MAIN_PUB=it.uniparthenope.reti.pub.server.PubServer
MAIN_WAITER=it.uniparthenope.reti.pub.server.WaiterServer
MAIN_CLIENT=it.uniparthenope.reti.pub.client.CustomerClient
MAIN_GUI=it.uniparthenope.reti.pub.client.CustomerGuiClient

.PHONY: all compile clean pub waiter client gui

all: compile

compile:
	mkdir -p $(OUT_DIR)
	javac -encoding UTF-8 -d $(OUT_DIR) $(SRC_FILES)

pub: compile
	java -cp $(OUT_DIR) $(MAIN_PUB)

waiter: compile
	java -cp $(OUT_DIR) $(MAIN_WAITER)

client: compile
	java -cp $(OUT_DIR) $(MAIN_CLIENT)

gui: compile
	java -cp $(OUT_DIR) $(MAIN_GUI)

clean:
	rm -rf $(OUT_DIR)
