SERVICE_NAME_PY=wipe_dev03
SERVICE_NAME=wipe_dev03
URL= http://dev03.berkeley.kbase.us:9000

test: 
	which python
	python --version
	java -version
	./cfg_to_runner.py $(TESTCFG)
	./run_tests.sh

compile: compile-typespec compile-typespec-java

compile-typespec:
	mkdir -p lib/biokbase/$(SERVICE_NAME_PY)
	touch lib/biokbase/__init__.py #do not include code in biokbase/__init__.py
	touch lib/biokbase/$(SERVICE_NAME_PY)/__init__.py 
	mkdir -p lib/javascript/$(SERVICE_NAME)
	compile_typespec \
		--pyserver biokbase.$(SERVICE_NAME_PY).server \
		--pyimpl biokbase.$(SERVICE_NAME_PY).impl \
		--client Bio::KBase::$(SERVICE_NAME_PY)::Client \
		--py biokbase/$(SERVICE_NAME_PY)/client \
		--js javascript/$(SERVICE_NAME)/Client \
		--service $(SERVICE_NAME)Server \
		--impl $(SERVICE_NAME)Impl \
		--url $(URL) \
		$(SERVICE_NAME).spec lib
	-rm lib/$(SERVICE_NAME)Server.pm
	-rm lib/$(SERVICE_NAME)Impl.pm
	-rm -r biokbase

compile-typespec-java:
	gen_java_types -o . -u $(URL) $(SERVICE_NAME).spec
	-rm lib/*.jar