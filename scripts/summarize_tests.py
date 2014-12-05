'''
Created on Dec 4, 2014

@author: gaprice@lbl.gov
'''

from collections import defaultdict
from urllib2 import urlopen
import json

START_JOB = 30
STOP_JOB = 40

JENKINS_URL = 'https://jenkins.kbase.us'
TEST_SUITE_NAME = 'jgi_kbase_integration_test'


def collect_test_data():
    url_prefix = JENKINS_URL + '/job/' + TEST_SUITE_NAME + '/'
    res = defaultdict(lambda: defaultdict(list))
    testcount = defaultdict(int)
    for job in xrange(START_JOB, STOP_JOB + 1):
        url = url_prefix + str(job) + "/testReport/api/json"
        j = json.loads(urlopen(url).read())
        for test in j['suites'][0]['cases']:
            name = test['name']
            error = test['errorDetails']
            testcount[name] += 1
            if error:
                res[name][error].append(job)
    return testcount, res


def print_test_data(testcount, res):
    pass


def main():
    testcount, res = collect_test_data()
    print json.dumps(res, indent=4)
    print json.dumps(testcount, indent=4)
    print_test_data(testcount, res)

if __name__ == '__main__':
    main()
