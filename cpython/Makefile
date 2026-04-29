IMAGE_NAME := aviator-python-builder
DOCKER_RUN := docker run --rm -v $(CURDIR):/workspace -v $(CURDIR)/build:/workspace/build

.PHONY: all build rebuild install clean test docker-image docker-build docker-rebuild docker-shell

all: build

build:
	@chmod +x scripts/build.sh
	./scripts/build.sh build

rebuild:
	@chmod +x scripts/build.sh
	./scripts/build.sh rebuild

install:
	@chmod +x scripts/build.sh
	./scripts/build.sh install

clean:
	@chmod +x scripts/build.sh
	./scripts/build.sh clean

test:
	cd ../aviator-python-runtime && mvn test -Dtest=PythonToolTest

docker-image:
	docker build -t $(IMAGE_NAME) .

docker-build: docker-image
	$(DOCKER_RUN) $(IMAGE_NAME) build

docker-rebuild: docker-image
	$(DOCKER_RUN) $(IMAGE_NAME) rebuild

docker-shell: docker-image
	$(DOCKER_RUN) -it --entrypoint /bin/bash $(IMAGE_NAME)
