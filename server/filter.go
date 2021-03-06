package main

// #cgo LDFLAGS: -lm
// #include "madgwick.h"
import "C"

import (
	"log"
	"math"
	"./stepsproto"
)

// 25 ms
const resamplePeriod = 25000000

// msg types to be multiplexed
// this also determines the order of contatenated values
var types = [...]string{"gyr", "acc", "mag"}

type ValueMap map[string][]float32

func (s ValueMap) complete() bool {
	return len(s) == len(types)
}

// remsgd and multiplexed collection of msgs
type MuxMessage struct {
	timestamp int64
	values ValueMap
	next *MuxMessage
}

// remsg range
type Range struct {

	// next interpolated timestamp
	timestamp int64
	last *stepsproto.Message
}

type Filter struct {

	name string

	// empty root element in mux queue
	root *MuxMessage

	// interpolation ranges
	ranges map[string]*Range

	// first interpolation instant defined
	started bool

	// madgwick orientation state
	orientation *C.struct_orientation
}

func NewFilter(name string) *Filter {
	log.Println("start trace", name)
	filter := &Filter{
		name: name,
		ranges: make(map[string]*Range),
		root: &MuxMessage{},
		started: false,
		orientation: &C.struct_orientation{},
	}
	for i := range types {
		filter.ranges[types[i]] = &Range{}
	}
	C.madgwick_init(filter.orientation)
	return filter
}

func (f *Filter) complete() bool {
	for _, r := range f.ranges {
		if r.last == nil {
			return false
		}
	}
	return true
}

var sampleFreq float32 = 1.0 / (resamplePeriod / 1e9)
const beta float32 = 0.04
const rad2deg float64 = 180 / math.Pi

func (f *Filter) madgwick(timestamp int64, values []float32) {
	C.madgwick_update_array(
		f.orientation, C.float(sampleFreq), C.float(beta),
		(*_Ctype_float)(&values[0]))

	// quaternion slice
	o := f.orientation
	q := []float32{
		float32(o.q0),
		float32(o.q1),
		float32(o.q2),
		float32(o.q3),
	}
	q1 := float64(o.q0)
	q2 := float64(o.q1)
	q3 := float64(o.q2)
	q4 := float64(o.q3)

	// euler angles in radians (madgwick 2010)
	z := math.Atan2(2*q2*q3 - 2*q1*q4, 2*q1*q1 + 2*q2*q2 - 1) * rad2deg
	y := -math.Asin(2*q2*q4 + 2*q1*q3) * rad2deg
	x := math.Atan2(2*q3*q4 - 2*q1*q2, 2*q1*q1 + 2*q4*q4 - 1) * rad2deg
	e := []float64{z, y, x}

	if false {
		log.Println("qtn", timestamp, q)
		log.Println("eul", timestamp, e)
	}

	broadcast(timestamp, "qtn", q)
}

func (f *Filter) outputMux(msg *MuxMessage) {
	id := ""
	values := make([]float32, 0)
	for _, t := range types {
		id += t[0:1]
		values = append(values, msg.values[t]...)
	}
	//log.Println(id, msg.timestamp, values, "mux")
	f.madgwick(msg.timestamp, values)
}

func (f *Filter) outputInterpolate(
		timestamp int64,
		id string,
		values []float32) {
	//log.Println(id, timestamp, values, "interp")

	prev := f.root
	for prev.next != nil && prev.next.timestamp < timestamp {
		prev = prev.next
	}
	if prev.next == nil {
		m := &MuxMessage{
			timestamp: timestamp,
			values: ValueMap{},
		}
		prev.next = m
	}
	prev.next.values[id] = values
	if !prev.next.values.complete() {
		return
	}
	msg := prev.next
	prev.next = msg.next
	f.outputMux(msg)
}

// entry point

func (f *Filter) Send(msg *stepsproto.Message) {
	f.mux(msg)
	//h.broadcast <- msg
}

func broadcast(timestamp int64, id string, values []float32) {
	msg := &stepsproto.Message{
		Type: stepsproto.Message_SENSOR_EVENT.Enum(),
		Timestamp: &timestamp,
		SensorId: &id,
		Value: values,
	}
	h.broadcast <- msg
}

func (f *Filter) mux(msg *stepsproto.Message) {

	if msg.GetType() != stepsproto.Message_SENSOR_EVENT {
		return
	}
	if msg.GetSensorId() == "rot" {
		h.broadcast <- msg
		return
	}

	id := msg.GetSensorId()
	vals := msg.GetValue()
	timestamp := msg.GetTimestamp()

	//defer log.Println(id, timestamp, vals)

	if !f.started {
		f.ranges[id].last = msg
		if f.complete() {
			// set range timestamps
			for rid, r := range f.ranges {
				if rid == id {
					r.timestamp = timestamp + resamplePeriod
				} else {
					r.timestamp = timestamp
				}
			}
			f.outputInterpolate(timestamp, id, vals)
			f.started = true
		}
		return
	}

	r := f.ranges[id]
	if timestamp < r.timestamp {
		r.last = msg
		return
	}

	// calculate interpolated values

	sampleCount := int(timestamp - r.timestamp) / resamplePeriod + 1
	prevValues := r.last.GetValue()
	prevTimestamp := r.last.GetTimestamp()
	prevTimeDiff := timestamp - prevTimestamp

	deltas := make([]float64, len(prevValues))
	values := make([]float32, len(prevValues))

	// calculate deltas
	for i := range vals {
		valueDiff := float64(vals[i]) - float64(prevValues[i])
		deltas[i] = valueDiff / float64(prevTimeDiff)
	}

	// interpolate
	t := r.timestamp
	for j := 0; j < sampleCount; j++ {
		dt := t - prevTimestamp
		for i := range prevValues {
			values[i] = float32(float64(prevValues[i]) +
				deltas[i] * float64(dt))
		}
		f.outputInterpolate(t, id, values)
		t += resamplePeriod
	}

	// restore last
	r.last = msg
	r.timestamp = t
}
