#! /usr/bin/env python
'''
Created on Dec 4, 2014

@author: gaprice@lbl.gov
'''

from __future__ import print_function
from collections import defaultdict
from urllib2 import urlopen
import json

START_JOB = 30
STOP_JOB = 60

JENKINS_URL = 'https://jenkins.kbase.us'
TEST_SUITE_NAME = 'jgi_kbase_integration_test'


def collect_test_data():
    url_prefix = JENKINS_URL + '/job/' + TEST_SUITE_NAME + '/'
    res = defaultdict(lambda: defaultdict(list))
    testcount = defaultdict(int)
    for job in xrange(START_JOB, STOP_JOB + 1):
        url = url_prefix + str(job) + '/testReport/api/json'
        j = json.loads(urlopen(url).read())
        for test in j['suites'][0]['cases']:
            name = test['name']
            error = test['errorDetails']
            testcount[name] += 1
            if error:
                res[name][error].append(job)
    return testcount, res


def print_test_summary_data(testcount, res):
    total = 0
    fails = 0
    print('Test\tTotal\tFails')
    for test in testcount:
        test_fails = 0
        if test in res:
            for error in res[test]:
                test_fails += len(res[test][error])
        print(test + '\t' + str(testcount[test]) + '\t' + str(test_fails))
        total += testcount[test]
        fails += test_fails
    print('TTL\t' + str(total) + '\t' + str(fails))


def print_test_errors(res):
    print('Test\tError\tCount\tTest IDs')
    for test in res:
        for error in res[test]:
            print(test + '\t' + error + '\t' + str(len(res[test][error])) +
                  '\t' + ','.join(map(str, res[test][error])))


def main():
    testcount, res = collect_test_data()

    print_test_summary_data(testcount, res)
    print()
    print_test_errors(res)

if __name__ == '__main__':
    main()
