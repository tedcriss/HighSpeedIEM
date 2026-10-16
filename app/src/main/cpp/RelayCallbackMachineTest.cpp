// Debug-only behavioral tests exercise the production machine with injected IO.
#ifndef NDEBUG
#include "RelayEngine.h"
#include <algorithm>
#include <cstring>
#include <limits>
#include <thread>
#include <sys/eventfd.h>
#include <unistd.h>

namespace {
struct Fake {
    int got = 0, calls = 0, want = 0, inputXrun = 0, outputXrun = 0, size = 64;
    int64_t available = 0;
    int32_t (*supply)(void*, int32_t) = nullptr;
};
thread_local Fake* fake;
int32_t readFake(AAudioStream*, void* data, int32_t n, int64_t timeout) {
    ++fake->calls; fake->want = n;
    if (timeout != 0) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    if (fake->supply) return fake->supply(data, n);
    return fake->got;
}
int64_t writtenFake(AAudioStream*) { return fake->available; }
int64_t readFramesFake(AAudioStream*) { return 0; }
int32_t xrunFake(AAudioStream* s) { return reinterpret_cast<uintptr_t>(s) == 1 ? fake->inputXrun : fake->outputXrun; }
int32_t sizeFake(AAudioStream*) { return fake->size; }
}

std::string runCallbackMachineTests() {
    int checks = 0;
    auto check = [&](bool condition, const char* what) { ++checks; if (!condition) throw std::runtime_error(what); };
    try {
        RelayEngine e;
        Fake f; fake = &f;
        e.input = reinterpret_cast<AAudioStream*>(1); e.output = reinterpret_cast<AAudioStream*>(2);
        e.inputCapacity = e.outputCapacity = 64; e.inChannels = 2; e.outChannels = 2;
        e.inputBytesPerFrame = e.outputBytesPerFrame = 4; e.format = AAUDIO_FORMAT_PCM_I16;
        e.outBurst = 8; e.outRate = 64; e.scratch.resize(256); e.callbackReady = true;
        e.routingMasks[0] = 3; e.routingMasks[1] = 0;
        auto& m = e.callbackMachine;
        m.ops = {readFake, writtenFake, readFramesFake, xrunFake, sizeFake};
        std::array<int16_t, 512> out{};
        auto process = [&] { out.fill(1234); return m.process(out.data(), 8); };
        auto silent = [&] { return std::all_of(out.begin(), out.begin()+16, [](auto x){return x == 0;}); };
        auto reset = [&] {
            e.events.stopRequested = false; e.events.errorState = 0; e.events.head = 0; e.events.tail = 0;
            e.events.overflowState.reset(); e.events.overflowXrun.reset(); e.events.overflowGate.reset();
            e.events.overflowInputTotal = e.events.overflowOutputTotal = 0;
            f = {}; e.callbackReady = true; m.prepare(0,0);
        };
        reset();
        check(e.events.head == 0, "initial DRAIN must not notify");
        for (int i=0;i<3;++i) { process(); check(m.state == RelayCallbackMachine::State::DRAIN && f.want == 64, "early DRAIN/read size"); }
        process(); check(m.state == RelayCallbackMachine::State::DISCARD && f.calls == 4 && silent(), "DRAIN four stable, no fallthrough");
        for(int i=0;i<4;++i) { process(); check(m.state == RelayCallbackMachine::State::DISCARD && f.want == 8, "DISCARD early/size"); }
        process(); check(m.state == RelayCallbackMachine::State::RUNNING && f.calls == 9 && silent(), "DISCARD five stable/no fallthrough");
        reset(); process(); process(); f.available=100; process(); f.available=0;
        for(int i=0;i<3;++i) process();
        check(m.state == RelayCallbackMachine::State::DRAIN, "unstable DRAIN resets streak");
        process(); check(m.state == RelayCallbackMachine::State::DISCARD, "reset streak then four");
        reset(); f.available=-1;
        for(int i=0;i<199;++i) process();
        check(m.state == RelayCallbackMachine::State::DRAIN && f.calls==199, "unknown availability never stable");
        process(); check(m.state == RelayCallbackMachine::State::DISCARD && f.calls==200, "DRAIN cap exactly 200");
        f.got=8;
        for(int i=0;i<149;++i) process();
        check(m.state == RelayCallbackMachine::State::DISCARD, "unknown DISCARD no false difference");
        process(); check(m.state == RelayCallbackMachine::State::RUNNING, "DISCARD cap exactly 150");
        f.got=0;
        for (int cycle=0;cycle<1000;++cycle) {
            m.backlogThresholdFrames=10; f.available=11;
            const int before=f.calls; process();
            check(m.state == RelayCallbackMachine::State::DRAIN && f.calls==before && silent(), "backlog must transition without read");
            f.available=0;
            for(int j=0;j<9;++j) process();
            check(m.state == RelayCallbackMachine::State::RUNNING, "recovery loops must remain unlimited");
        }
        m.backlogThresholdFrames=0;
        f.inputXrun=1; f.outputXrun=2; int before=f.calls; process();
        check(f.calls==before+1 && f.want==64 && silent(), "combined XRun one capacity read and silence");
        before=f.calls; process(); check(f.calls==before+1 && f.want==8, "XRun baselines updated before return");
        ++f.outputXrun; before=f.calls; process(); check(f.calls==before && silent(), "output-only XRun no read");
        ++f.inputXrun; before=f.calls; process(); check(f.calls==before+1 && f.want==64 && silent(), "input-only XRun");
        f.inputXrun=f.outputXrun=-1; before=f.calls; process(); check(f.calls==before+1, "unavailable XRun must keep reading");
        f.inputXrun=f.outputXrun=100; process(); check(f.want==8, "recovered XRun establishes baseline only");
        f.inputXrun=f.outputXrun=0; process(); check(f.want==8, "decreased XRun not huge delta");
        m.inputThresholdFrames=10; f.available=0;
        before=f.calls;
        for(int i=0;i<8;++i) { process(); check(silent(), "gated output silence"); }
        check(f.calls==before, "gate holds for one second");
        process(); check(f.calls==before+1, "gate stall permits one read");
        process(); check(f.calls==before+1, "gate resumes after one read");
        f.available=-1; process(); check(f.calls==before+2, "unknown availability opens gate");
        f.size=4; f.available=4; process(); check(f.calls==before+3, "gate clamps actual input size");
        m.inputThresholdFrames=0;
        f.supply=[](void* data,int n)->int32_t { auto* x=static_cast<int16_t*>(data); for(int i=0;i<n;++i) {x[2*i]=100;x[2*i+1]=300;} return n; };
        process(); check(out[0]==200 && out[1]==0 && out[14]==200, "I16 mean and unrouted silence");
        f.supply=[](void* data,int)->int32_t { auto* x=static_cast<int16_t*>(data);x[0]=200;x[1]=400;return 1; };
        before=f.calls;process();check(out[0]==300 && out[1]==0 && out[2]==0 && f.calls==before+1, "short read one request and zero tail");
        f.supply=nullptr;f.got=0;process();check(silent(), "zero read all silence");
        // All four PCM mixer representations, including packed sign extension.
        for(auto format : {AAUDIO_FORMAT_PCM_FLOAT,AAUDIO_FORMAT_PCM_I32,AAUDIO_FORMAT_PCM_I24_PACKED}) {
            e.format=format;
            if(format==AAUDIO_FORMAT_PCM_FLOAT) f.supply=[](void* d,int n)->int32_t {auto*x=static_cast<float*>(d);for(int i=0;i<n;++i){x[2*i]=0.25f;x[2*i+1]=0.75f;}return n;};
            if(format==AAUDIO_FORMAT_PCM_I32) f.supply=[](void* d,int n)->int32_t {auto*x=static_cast<int32_t*>(d);for(int i=0;i<n;++i){x[2*i]=-100;x[2*i+1]=300;}return n;};
            if(format==AAUDIO_FORMAT_PCM_I24_PACKED) f.supply=[](void* d,int n)->int32_t {auto*x=static_cast<uint8_t*>(d);for(int i=0;i<n;++i){x[6*i]=0x9c;x[6*i+1]=0xff;x[6*i+2]=0xff;x[6*i+3]=100;x[6*i+4]=0;x[6*i+5]=0;}return n;};
            e.inputBytesPerFrame=e.outputBytesPerFrame=format==AAUDIO_FORMAT_PCM_I24_PACKED?6:8;
            process();
            check(format==AAUDIO_FORMAT_PCM_FLOAT ? reinterpret_cast<float*>(out.data())[0]==0.5f :
                format==AAUDIO_FORMAT_PCM_I32 ? reinterpret_cast<int32_t*>(out.data())[0]==100 : out[0]==0, "PCM format mixing");
        }
        e.format=AAUDIO_FORMAT_PCM_I16;e.inputBytesPerFrame=e.outputBytesPerFrame=4;f.supply=nullptr;
        f.got=AAUDIO_ERROR_DISCONNECTED;check(process()==AAUDIO_CALLBACK_RESULT_STOP && silent() && e.events.exiting(), "negative read fatal + silence");
        before=f.calls;check(process()==AAUDIO_CALLBACK_RESULT_STOP && f.calls==before, "EXIT sticky and no read");
        for (auto phase : {RelayCallbackMachine::State::DRAIN, RelayCallbackMachine::State::DISCARD}) {
            reset(); if (phase == RelayCallbackMachine::State::DISCARD) m.transitionTo(phase);
            f.got=AAUDIO_ERROR_DISCONNECTED;
            check(process()==AAUDIO_CALLBACK_RESULT_STOP && silent() && f.calls==1, "phase read failure stops silently");
        }
        reset(); f.got=0;
        check(m.process(out.data(),64)==AAUDIO_CALLBACK_RESULT_CONTINUE,"scratch exact fit");
        check(m.process(out.data(),65)==AAUDIO_CALLBACK_RESULT_STOP,"one frame over scratch must stop");
        reset();e.scratch.resize(255);check(process()==AAUDIO_CALLBACK_RESULT_STOP && f.calls==0,"capacity byte boundary before read");e.scratch.resize(256);
        reset();check(m.process(nullptr,8)==AAUDIO_CALLBACK_RESULT_STOP,"null output rejected");
        reset();check(m.process(out.data(),-1)==AAUDIO_CALLBACK_RESULT_STOP,"negative frames rejected");
        size_t product=0;
        check(!RelayCallbackMachine::checkedMultiply(std::numeric_limits<size_t>::max(),2,product),"size_t overflow");
        check(RelayCallbackMachine::checkedMultiply(64,4,product)&&product==256,"checked multiplication");
        // Fill the ordinary queue without a consumer. Fatal first-error storage
        // remains independent, including simultaneous producers.
        reset();
        for(int i=0;i<10000;++i)e.events.publish(RelayEventType::XRun,1,2);
        RelayEvent overflow;
        uint32_t seen = 0;
        check(e.events.overflowXrun.take(seen, overflow, 0) && overflow.value1 == 9936 && overflow.value2 == 19872,
              "overflow must retain both XRun totals");
        e.events.publish(RelayEventType::Drain); e.events.publish(RelayEventType::Running);
        seen = 0;
        check(e.events.overflowState.take(seen, overflow, 0) && overflow.type == RelayEventType::Running,
              "congestion retains latest state");
        RelayEventDispatcher::OverflowSlot slot;
        std::atomic<bool> producerDone{false};
        std::thread producer([&] {
            for (int j=1;j<=20000;++j) slot.store({0,static_cast<uint32_t>(j),RelayEventType::XRun,j,int64_t(j)*2});
            producerDone.store(true,std::memory_order_release);
        });
        seen = 0;
        bool consistent = true;
        while (!producerDone.load(std::memory_order_acquire)) {
            if (slot.take(seen,overflow,0) && overflow.value2 != overflow.value1 * 2) consistent = false;
        }
        producer.join();
        if (slot.take(seen,overflow,0) && overflow.value2 != overflow.value1 * 2) consistent = false;
        check(consistent && overflow.value1==20000,"concurrent split snapshots must be coherent and reach latest totals");
        std::thread a([&]{e.events.requestExit({RelayErrorKind::Read,-899,2,8,32,256});});
        std::thread b([&]{e.events.requestExit({RelayErrorKind::Platform,-898,2,0,0,0});});a.join();b.join();
        check(e.events.errorState==2 && e.events.exiting(),"congested queue retains first fatal from multiple producers");
        const int code=e.events.firstError.code;
        e.events.requestExit({RelayErrorKind::Read,-123,0,0,0,0});
        check(e.events.firstError.code==code,"first fatal preserved");
        // A real dispatcher waiter can be stopped and re-created without an
        // audio callback; FD lifetime spans all producers and the consumer.
        e.input=nullptr;e.output=nullptr;
        for(int i=0;i<20;++i) {check(e.events.prepare(e,i).empty(),"dispatcher prepare");e.events.stop();check(e.events.fd==-1,"dispatcher joined and FD closed");}
        return "PASS " + std::to_string(checks) + " behavioral assertions";
    } catch(const std::exception& e) { return "FAIL after " + std::to_string(checks) + ": " + e.what(); }
}
#endif
